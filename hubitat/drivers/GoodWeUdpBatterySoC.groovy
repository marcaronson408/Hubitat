metadata {
	definition(name: "GoodWe UDP Battery SoC", namespace: "local.goodwe", author: "AI Assistant") {
		capability "Battery"
		capability "Refresh"
		capability "Initialize"
		attribute "stateOfCharge", "number"
	}
}

preferences {
	input name: "inverterIp", type: "string", title: "GoodWe Inverter IP Address", required: true
	input name: "inverterPort", type: "number", title: "UDP Port", defaultValue: 8899, required: true
	input name: "requestHex", type: "string", title: "Request Payload (hex)", defaultValue: "AA55C07F0102000241", required: true
	input name: "socOffset", type: "number", title: "SoC Byte Offset", description: "Offset of SoC byte in response", defaultValue: 173, required: true
	input name: "socLength", type: "enum", title: "SoC Length (bytes)", options: ["1", "2"], defaultValue: "1", required: true
	input name: "bigEndian", type: "bool", title: "Big-endian (for 2-byte SoC)", defaultValue: true
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
	Integer interval = (settings?.pollIntervalSeconds as Integer) ?: 120
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
	Integer interval = seconds ?: ((settings?.pollIntervalSeconds as Integer) ?: 120)
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
		String hex = (settings.requestHex as String)?.replaceAll(/\s+/, "")
		if (!hex || (hex.length() % 2) != 0) {
			log.warn "Invalid requestHex; must be even-length hex"
			return
		}
		byte[] bytes = hubitat.helper.HexUtils.hexStringToByteArray(hex)
		String payload = new String(bytes, 'ISO-8859-1')
		String ip = settings.inverterIp as String
		Integer port = (settings.inverterPort as Integer) ?: 8899
		if (debugLogging) log.debug "TX UDP ${ip}:${port} ${hex}"
		interfaces.udpSocket.sendMessage(payload, ip, port)
	} catch (Throwable t) {
		log.error "Failed to send UDP poll: ${t?.message}"
	}
	scheduleNextPoll()
}

void parse(String description) {
	try {
		def msg = parseLanMessage(description)
		String payloadHex = msg?.payload
		if (!payloadHex) {
			if (debugLogging) log.debug "UDP parse(): no payload"
			return
		}
		byte[] data = hubitat.helper.HexUtils.hexStringToByteArray(payloadHex)
		if (debugLogging) log.debug "RX UDP len=${data.length}"
		Integer soc = extractSocFromUdpResponse(data)
		if (soc != null) {
			sendEvent(name: "battery", value: soc, unit: "%")
			sendEvent(name: "stateOfCharge", value: soc, unit: "%")
			logInfo "Battery SoC: ${soc}%"
		} else {
			log.warn "Unable to parse SoC from UDP response"
		}
	} catch (Throwable t) {
		log.error "parse() error: ${t?.message}"
	}
}

private Integer extractSocFromUdpResponse(byte[] bytes) {
	Integer offset = (settings.socOffset as Integer)
	String lenStr = (settings.socLength as String)
	Integer len = lenStr ? Integer.parseInt(lenStr) : 1
	if (!bytes || offset == null) return null
	if (offset < 0 || offset >= bytes.length) return null
	int raw
	if (len == 1) {
		raw = (bytes[offset] & 0xFF)
	} else if (len == 2) {
		if (offset + 1 >= bytes.length) return null
		boolean be = (settings.bigEndian as Boolean)
		int b0 = (bytes[offset] & 0xFF)
		int b1 = (bytes[offset + 1] & 0xFF)
		raw = be ? ((b0 << 8) | b1) : ((b1 << 8) | b0)
	} else {
		return null
	}
	BigDecimal divisor = (settings?.scaleDivisor ? settings.scaleDivisor as BigDecimal : 1G)
	BigDecimal scaled = divisor == 0 ? raw : (raw / divisor)
	Integer soc = Math.max(0, Math.min(100, scaled.toInteger()))
	return soc
}

private boolean validatePreferences() {
	if (!settings?.inverterIp) return false
	if (!(settings?.inverterPort as Integer)) return false
	if (!(settings?.requestHex as String)) return false
	return true
}

private void logInfo(String msg) {
	if (debugLogging) log.debug msg
	else log.info msg
}