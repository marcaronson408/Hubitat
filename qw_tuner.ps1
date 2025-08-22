param(
  [string]$ProcessName = 'qw'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Get-ActivePowerSchemeGuid {
  $output = powercfg /getactivescheme 2>&1
  if ($LASTEXITCODE -eq 0 -and $output -match 'GUID:\s*([a-f0-9\-]{36})') {
    return $Matches[1]
  }
  return $null
}

function Get-PreferredPerfSchemeGuid {
  $output = powercfg /list 2>&1
  $ultimate = 'e9a42b02-d5df-448d-aa00-03f14749eb61' # Ultimate Performance
  $high = '8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c'     # High performance
  if ($output -match $ultimate) { return $ultimate }
  if ($output -match $high) { return $high }
  return $null
}

function Set-ActiveScheme([string]$schemeGuid) {
  if ([string]::IsNullOrWhiteSpace($schemeGuid)) { return }
  powercfg /setactive $schemeGuid | Out-Null
}

function Ensure-GpuHighPerformancePreference([string]$exePath) {
  if ([string]::IsNullOrWhiteSpace($exePath)) { return }
  $regPath = 'HKCU:\Software\Microsoft\DirectX\UserGpuPreferences'
  New-Item -Path $regPath -Force | Out-Null
  # 2 = High performance GPU preference
  New-ItemProperty -Path $regPath -Name $exePath -PropertyType String -Value 'GpuPreference=2;' -Force | Out-Null
}

# C# helper to compute a Group 0 affinity mask that includes only Performance cores (EfficiencyClass == 0)
Add-Type -Language CSharp -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class CpuInfoHelper {
  public enum LOGICAL_PROCESSOR_RELATIONSHIP : int { RelationProcessorCore = 0, RelationNumaNode = 1, RelationCache = 2, RelationProcessorPackage = 3, RelationGroup = 4, RelationAll = 0xFFFF }
  [StructLayout(LayoutKind.Sequential)]
  struct SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX { public LOGICAL_PROCESSOR_RELATIONSHIP Relationship; public uint Size; }
  [StructLayout(LayoutKind.Sequential)]
  struct GROUP_AFFINITY { public UIntPtr Mask; public ushort Group; public ushort R1; public ushort R2; public ushort R3; }
  [DllImport("kernel32.dll", SetLastError=true)]
  static extern bool GetLogicalProcessorInformationEx(LOGICAL_PROCESSOR_RELATIONSHIP RelationshipType, IntPtr Buffer, ref uint ReturnedLength);
  public static ulong GetPcoreMaskGroup0() {
    uint len = 0;
    GetLogicalProcessorInformationEx(LOGICAL_PROCESSOR_RELATIONSHIP.RelationProcessorCore, IntPtr.Zero, ref len);
    IntPtr buf = Marshal.AllocHGlobal((int)len);
    try {
      if (!GetLogicalProcessorInformationEx(LOGICAL_PROCESSOR_RELATIONSHIP.RelationProcessorCore, buf, ref len)) return 0UL;
      IntPtr ptr = buf;
      IntPtr end = buf + (int)len;
      ulong mask = 0UL;
      while (ptr.ToInt64() < end.ToInt64()) {
        var rel = (LOGICAL_PROCESSOR_RELATIONSHIP)Marshal.ReadInt32(ptr, 0);
        uint size = (uint)Marshal.ReadInt32(ptr, 4);
        if (rel == LOGICAL_PROCESSOR_RELATIONSHIP.RelationProcessorCore) {
          // Processor relationship block starts at offset 8
          byte efficiencyClass = Marshal.ReadByte(ptr, 8 + 1);
          ushort groupCount = (ushort)Marshal.ReadInt16(ptr, 8 + 22);
          IntPtr groupPtr = ptr + 8 + 24;
          for (int i = 0; i < groupCount; i++) {
            UIntPtr m = (UIntPtr)Marshal.ReadIntPtr(groupPtr, 0);
            ushort group = (ushort)Marshal.ReadInt16(groupPtr, IntPtr.Size);
            if (group == 0 && efficiencyClass != 0) {
              ulong bits = m.ToUInt64();
              mask |= bits;
            }
            groupPtr += (IntPtr)(IntPtr.Size + 8);
          }
        }
        ptr += (int)size;
      }
      return mask;
    } finally { Marshal.FreeHGlobal(buf); }
  }
}
"@

# Capture original power plan and switch to performance
$originalScheme = Get-ActivePowerSchemeGuid
$targetScheme = Get-PreferredPerfSchemeGuid
$switchedScheme = $false
if ($targetScheme -and $targetScheme -ne $originalScheme) {
  try { Set-ActiveScheme $targetScheme; $switchedScheme = $true } catch {}
}

# Find or wait for qw.exe
$processes = Get-Process -Name $ProcessName -ErrorAction SilentlyContinue
if (-not $processes) {
  Write-Host "Waiting for $ProcessName.exe to start..."
  do {
    Start-Sleep -Seconds 1
    $processes = Get-Process -Name $ProcessName -ErrorAction SilentlyContinue
  } while (-not $processes)
}

# Compute P-core mask (group 0). If unavailable, skip affinity restriction
$pcoreMask = [CpuInfoHelper]::GetPcoreMaskGroup0()
if ($pcoreMask -eq 0) {
  Write-Host 'Warning: Could not determine P-core mask; skipping affinity restriction.'
}

# Apply settings to all matching processes
foreach ($proc in $processes) {
  try {
    if ($pcoreMask -ne 0) {
      $proc.ProcessorAffinity = [intptr]([uint64]$pcoreMask)
    }
    # Raise process base priority (also helps command processing and GPU command submission threads)
    $proc.PriorityClass = 'AboveNormal'
    # Ensure the app is set to use High Performance GPU going forward
    try { Ensure-GpuHighPerformancePreference -exePath $proc.MainModule.FileName } catch {}
  } catch {
    Write-Host "Failed to configure PID $($proc.Id): $_"
  }
}

# Wait for all of them to exit
try {
  Wait-Process -Id ($processes.Id)
} finally {
  if ($switchedScheme -and $originalScheme) {
    try { Set-ActiveScheme $originalScheme } catch {}
  }
}