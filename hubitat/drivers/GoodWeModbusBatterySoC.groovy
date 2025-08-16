metadata {
	definition(name: "GoodWe Modbus Battery SoC", namespace: "local.goodwe", author: "AI Assistant") {
		capability "Battery"
		capability "Refresh"
		capability "Initialize"
		attribute "stateOfCharge", "number"
	}
}

preferences {
	input name: "inverterIp", type: "string", title: "GoodWe Inverter IP Address", required: true
	input name: "inverterPort", type: "number", title: "Modbus TCP Port", defaultValue: 502, required: true
	input name: "unitId", type: "number", title: "Modbus Unit ID (Slave ID)", defaultValue: 247, required: true
	input name: "registerType", type: "enum", title: "Register Type", options: ["Holding (0x03)", "Input (0x04)"], defaultValue: "Holding (0x03)", required: true
	input name: "registerAddress", type: "number", title: "Register Address (SoC)", description: "Enter SoC register (commonly 11022 for ET/EH models; adjust as needed)", defaultValue: 11022, required: true
	input name: "addressIsOneBased", type: "bool", title: "Address is 1-based (Modbus spec style)", defaultValue: true, required: true
	input name: "scaleDivisor", type: "number", title: "Scale Divisor (e.g., 1 or 10)", defaultValue: 1, required: true
	input name: "pollIntervalSeconds", type: "number", title: "Poll Interval (seconds)", defaultValue: 120, required: true
	input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: true
}

void installed() {
	logInfo "Installed"
	initialize()
}

void updated() {
	logInfo "Updated"
	unschedule()
	initialize()
}

void initialize() {
	Integer interval = (settings?.pollIntervalSeconds as Integer) ?: 30
	if (interval < 120) interval = 120
	scheduleNextPoll(interval)
	runIn(2, "refresh", [overwrite: true])
	if (debugLogging) runIn(1800, "logsOff")
}

void logsOff() {
	device.updateSetting("debugLogging", [value: "false", type: "bool"])
	log.info "Debug logging disabled"
}

private void scheduleNextPoll(Integer seconds = null) {
	Integer interval = seconds ?: ((settings?.pollIntervalSeconds as Integer) ?: 30)
	if (interval < 120) interval = 120
	runIn(interval, "refresh", [overwrite: true])
}

void refresh() {
	if (!validatePreferences()) {
		log.warn "Preferences incomplete; not polling"
		scheduleNextPoll()
		return
	}
	try {
		String requestHex = buildModbusReadRequestHex()
		if (debugLogging) log.debug "TX: ${requestHex}"
		hubitat.device.HubAction action = new hubitat.device.HubAction(
			requestHex,
			hubitat.device.Protocol.LAN,
			[
				type: hubitat.device.HubAction.Type.LAN_TYPE_TCPCLIENT,
				destinationAddress: "${settings.inverterIp}:${(settings.inverterPort as Integer)}",
				encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
				callback: "parse"
			]
		)
		sendHubCommand(action)
	} catch (Throwable t) {
		log.error "Failed to send poll: ${t.message}", t
	}
	scheduleNextPoll()
}

void parse(String description) {
	try {
		def msg = parseLanMessage(description)
		String payloadHex = msg?.payload
		if (!payloadHex && description?.contains("payload:")) {
			Integer idx = description.indexOf("payload:")
			payloadHex = description.substring(idx + 8).trim()
		}
		if (!payloadHex) {
			if (debugLogging) log.debug "No payload in parse()"
			return
		}
		byte[] bytes = hubitat.helper.HexUtils.hexStringToByteArray(payloadHex)
		if (debugLogging) log.debug "RX len=${bytes?.length ?: 0}"
		Integer soc = extractSocFromResponse(bytes)
		if (soc != null) {
			sendEvent(name: "battery", value: soc, unit: "%")
			sendEvent(name: "stateOfCharge", value: soc, unit: "%")
			logInfo "Battery SoC: ${soc}%"
		} else {
			log.warn "Unable to parse SoC from response"
		}
	} catch (Throwable t) {
		log.error "parse() error: ${t.message}", t
	}
}

private Integer extractSocFromResponse(byte[] bytes) {
	if (!bytes || bytes.length < 11) return null
	int transactionId = ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF)
	int protocolId = ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF)
	int lengthField = ((bytes[4] & 0xFF) << 8) | (bytes[5] & 0xFF)
	int unitIdResp = (bytes[6] & 0xFF)
	int function = (bytes[7] & 0xFF)
	int byteCount = (bytes[8] & 0xFF)
	if (!(function == 0x03 || function == 0x04)) return null
	if (byteCount < 2 || bytes.length < 9 + byteCount) return null
	int hi = (bytes[9] & 0xFF)
	int lo = (bytes[10] & 0xFF)
	int raw = (hi << 8) | lo
	BigDecimal divisor = (settings?.scaleDivisor ? settings.scaleDivisor as BigDecimal : 1G)
	BigDecimal scaled = divisor == 0 ? raw : (raw / divisor)
	Integer soc = Math.max(0, Math.min(100, scaled.toInteger()))
	return soc
}

private String buildModbusReadRequestHex() {
	int unit = (settings.unitId as Integer)
	boolean holding = ((settings.registerType as String)?.startsWith("Holding"))
	int functionCode = holding ? 0x03 : 0x04
	int quantity = 1
	int addr = (settings.registerAddress as Integer)
	if ((settings.addressIsOneBased as Boolean)) {
		addr = Math.max(0, addr - 1)
	}
	int transactionId = ((state?.transactionId as Integer) ?: 1) & 0xFFFF
	state.transactionId = (transactionId + 1) & 0xFFFF
	String pdu = toHex(functionCode, 1) + toHex(addr, 2) + toHex(quantity, 2)
	int length = 1 + (pdu.length() / 2)
	String mbap = toHex(transactionId, 2) + toHex(0, 2) + toHex(length, 2) + toHex(unit, 1)
	String frame = mbap + pdu
	return frame
}

private static String toHex(int value, int numBytes) {
	int mask = (numBytes >= 4) ? -1 : ((1 << (numBytes * 8)) - 1)
	long masked = value & mask
	return String.format("%0" + (numBytes * 2) + "X", masked)
}

private boolean validatePreferences() {
	if (!settings?.inverterIp) return false
	if (!(settings?.inverterPort as Integer)) return false
	if (!(settings?.unitId as Integer)) return false
	if (!(settings?.registerAddress as Integer)) return false
	return true
}

private void logInfo(String msg) {
	if (debugLogging) log.debug msg
	else log.info msg
}