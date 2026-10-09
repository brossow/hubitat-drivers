/**
 *  Copyright (C) Sebastian YEPES — original work
 *  Copyright (C) Brent Rossow — modifications (v1.2.0+)
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Forked from https://github.com/syepes/Hubitat/tree/master/Drivers/Aeotec (v1.1.6)
 *  Changes in v1.2.0:
 *    - Fixed undefined variable in versionv1.VersionReport handler (firmware0 branch)
 *    - Fixed stateCheckInterval hour options (value 2 was labeled "1h" but scheduled every 2h; added true 1h option)
 *    - Fixed SwitchBinaryReport handler (was silently dropped; now updates switch state)
 *    - Fixed Hail handler (was silently dropped; now triggers a state refresh when param80=1)
 *    - Changed param80 default to 2 (Basic Report) so the hub receives immediate state change notifications
 *    - Removed powerlevelGet() from poll() and checkState() (Z-Wave signal level is not device state)
 *    - Changed on()/off() to use SwitchBinary commands (more correct than Basic for switch control)
 *    - Removed deprecated displayed: true parameter from sendEvent() calls in reset()
 *  Changes in v1.2.1:
 *    - Fixed Hail handler (parameter shadowed cmd(), so it threw instead of refreshing state)
 *    - Save Preferences now sends the configuration to the device (updated() return values are ignored by Hubitat)
 *    - configure() falls back to preference defaults when preferences have never been saved
 *    - Stopped writing kVAh readings into the energy (kWh) attribute
 *    - Removed generic fingerprints that could claim other metering switches at pairing
 *    - Simplified command encapsulation to zwaveSecureEncap(); removed dead MultiChannel handler
 *    - Firmware version stored as a padded string (1.05 vs 1.50 were indistinguishable)
 */

import groovy.transform.Field

@Field String VERSION = "1.2.1"

@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[1]

metadata {
  definition (name: "Aeotec Heavy Duty Smart Switch", namespace: "brossow", author: "Sebastian YEPES (original), Brent Rossow (fork)", importUrl: "https://raw.githubusercontent.com/brossow/hubitat-drivers/main/aeotec/heavy-duty-switch/aeotec-heavy-duty-switch.groovy") {
    capability "Actuator"
    capability "Switch"
    capability "Sensor"
    capability "Power Meter"
    capability "Current Meter"
    capability "Energy Meter"
    capability "Voltage Measurement"
    capability "Temperature Measurement"
    capability "Refresh"
    capability "Polling"
    capability "Configuration"
    capability "Initialize"

    command "clearState"
    command "reset"

    fingerprint mfr: "0086", prod: "0103", model: "004E", deviceJoinName: "Aeotec Heavy Duty Smart Switch" //US
    fingerprint mfr: "0086", prod: "0003", model: "004E", deviceJoinName: "Aeotec Heavy Duty Smart Switch" //EU
    fingerprint mfr: "0086", prod: "0003", deviceId: "004E", inClusters: "0x5E,0x86,0x72,0x98,0x56", outClusters: "0x5A,0x82" //EU
    fingerprint deviceId: "004E", inClusters: "0x5E,0x86,0x72,0x98,0x56", outClusters: "0x5A,0x82"

  }

  preferences {
    section { // General
      input name: "logLevel", title: "Log Level", type: "enum", options: LOG_LEVELS, defaultValue: DEFAULT_LOG_LEVEL, required: false
      input name: "logDescText", title: "Log Description Text", type: "bool", defaultValue: true, required: false
      input name: "stateCheckInterval", title: "State Check", description: "Check interval of the current state", type: "enum", options:[[0:"Disabled"], [5:"5min"], [10:"10min"], [15:"15min"], [30:"30min"], [1:"1h"], [2:"2h"], [3:"3h"], [4:"4h"], [6:"6h"], [8:"8h"], [12:"12h"]], defaultValue: 2, required: true
    }
    section { // Configuration
      input name: "switchAll", title: "Respond to switch all", description: "How does the switch respond to the 'Switch All' command", type: "enum", options:["Disabled", "Off Enabled", "On Enabled", "On And Off Enabled"], defaultValue: "On And Off Enabled", required: true
      input name: "param20", title: "Default Load state", description: "Used for indicating the default state of output load after re-power on", type: "enum", options:[[0:"Last state after power on"],[1:"Always on after re-power on"],[2:"Always off after re-power on"]], defaultValue: 0, required: true
      input name: "param3", title: "Current Overload Protection", description: "The means the load will be disconnected after 5 seconds when the current more than 39.5A", type: "enum", options:[[0:"Disabled"],[1:"Enabled"]], defaultValue: 1, required: true
      input name: "param111", title: "Report interval", description: "", type: "enum", options:[[30:"30s"], [60:"1m"], [120:"2m"], [180:"3m"], [240:"4m"], [300:"5m"], [600:"10m"], [900:"15m"], [1800:"30m"], [3600:"1h"], [7200:"2h"], [10800:"3h"], [14400:"4h"], [21600:"6h"], [28800:"8h"], [32400:"9h"], [43200:"12h"], [86400:"24h"]], defaultValue: 300, required: true
      input name: "param101_voltage", title: "Report Instantaneous Voltage", description: "", type: "bool", defaultValue: true, required: true
      input name: "param101_current", title: "Report Instantaneous Current (Amperes)", description: "", type: "bool", defaultValue: true, required: true
      input name: "param101_watts", title: "Report Instantaneous Watts", description: "", type: "bool", defaultValue: true, required: true
      input name: "param101_currentUsage", title: "Report Accumulated kWh", description: "", type: "bool", defaultValue: true, required: true
      input name: "report_temp", title: "Report Temperature", description: "", type: "bool", defaultValue: false, required: true
      input name: "param80", title: "Load change notifications", description: "Send notifications when the state of the load is changed", type: "enum", options:[[0:"Send Nothing (Disabled)"],[1:"Send HAIL Command"],[2:"Send BASIC Report Command"]], defaultValue: 2, required: true
      input name: "param91", title: "Minimum change in wattage", description: "Report when the change of the current power is more/less than the threshold in wattage", type: "number", range: "0..32767", defaultValue: 50, required: true
      input name: "param92", title: "Minimum change in percentage", description: "Report when the change of the current power is more/less than the threshold in percentage", type: "number", range: "0..100", defaultValue: 10, required: true
    }
  }
}

def installed() {
  logger("debug", "installed(${VERSION})")

  if (state.driverInfo == null || state.driverInfo.isEmpty() || state.driverInfo.ver != VERSION) {
    state.driverInfo = [ver:VERSION]
  }

  if (state.deviceInfo == null) {
    state.deviceInfo = [:]
  }

  initialize()
}

def initialize() {
  logger("debug", "initialize()")
}

def updated() {
  logger("debug", "updated()")

  if (!state.driverInfo?.ver || state.driverInfo.isEmpty() || state.driverInfo.ver != VERSION) {
    installed()
  }

  // Hubitat ignores updated()'s return value, so the commands must be sent explicitly
  sendHubCommand(new hubitat.device.HubMultiAction(configure(), hubitat.device.Protocol.ZWAVE))
}

def poll() {
  logger("debug", "poll()")

  cmdSequence([
    zwave.sensorMultilevelV5.sensorMultilevelGet(sensorType: 1, scale: (location.temperatureScale=="F"?1:0)),
    zwave.basicV1.basicGet(),
    zwave.meterV4.meterGet(scale: 0), // energy kWh
    zwave.meterV4.meterGet(scale: 2), // watts
    zwave.meterV4.meterGet(scale: 4), // volts
    zwave.meterV4.meterGet(scale: 5)  // amps
  ])
}

def pollTemp() {
  logger("debug", "pollTemp()")
  // The temperature sensor only measures the internal temperature of product (Circuit board)
  cmd(zwave.sensorMultilevelV5.sensorMultilevelGet(sensorType: 1, scale: (location.temperatureScale=="F"?1:0)))
}

def refresh() {
  logger("debug", "refresh() - state: ${state.inspect()}")

  cmdSequence([
    zwave.versionV2.versionGet(),
    zwave.firmwareUpdateMdV2.firmwareMdGet(),
    zwave.manufacturerSpecificV2.manufacturerSpecificGet(),
    zwave.sensorMultilevelV5.sensorMultilevelGet(sensorType: 1, scale: (location.temperatureScale=="F"?1:0)),
    zwave.basicV1.basicGet(),
    zwave.meterV4.meterGet(scale: 0), // energy kWh
    zwave.meterV4.meterGet(scale: 2), // watts
    zwave.meterV4.meterGet(scale: 4), // volts
    zwave.meterV4.meterGet(scale: 5)  // amps
  ])
}

def on() {
  logger("debug", "on()")

  cmdSequence([
    zwave.switchBinaryV1.switchBinarySet(switchValue: 0xFF),
    zwave.switchBinaryV1.switchBinaryGet()
  ])
}

def off() {
  logger("debug", "off()")

  cmdSequence([
    zwave.switchBinaryV1.switchBinarySet(switchValue: 0x00),
    zwave.switchBinaryV1.switchBinaryGet()
  ])
}

def configure() {
  logger("debug", "configure()")
  def cmds = []

  unschedule()

  Integer interval = settingInt("stateCheckInterval", 2)
  if (interval) {
    String cron = ([5, 10, 15, 30].contains(interval) ?
      "6 */${interval} * ? * *" :
      "6 0 */${interval} ? * *" );

    schedule(cron, checkState)

    if (report_temp) {
      schedule(cron, pollTemp)
    }
  }

  def switchAllMode = hubitat.zwave.commands.switchallv1.SwitchAllSet.MODE_INCLUDED_IN_THE_ALL_ON_ALL_OFF_FUNCTIONALITY
  if (switchAll == "Disabled") {
    switchAllMode = hubitat.zwave.commands.switchallv1.SwitchAllSet.MODE_EXCLUDED_FROM_THE_ALL_ON_ALL_OFF_FUNCTIONALITY
  } else if (switchAll == "Off Enabled") {
    switchAllMode = hubitat.zwave.commands.switchallv1.SwitchAllSet.MODE_EXCLUDED_FROM_THE_ALL_ON_FUNCTIONALITY_BUT_NOT_ALL_OFF
  } else if (switchAll == "On Enabled") {
    switchAllMode = hubitat.zwave.commands.switchallv1.SwitchAllSet.MODE_EXCLUDED_FROM_THE_ALL_OFF_FUNCTIONALITY_BUT_NOT_ALL_ON
  }

  Integer reportGroup;
  reportGroup = (settingBool("param101_voltage", true) ? 1 : 0)
  reportGroup += (settingBool("param101_current", true) ? 2 : 0)
  reportGroup += (settingBool("param101_watts", true) ? 4 : 0)
  reportGroup += (settingBool("param101_currentUsage", true) ? 8 : 0)

  cmds = cmds + cmdSequence([
    zwave.switchAllV1.switchAllSet(mode: switchAllMode),
    zwave.associationV2.associationSet(groupingIdentifier:1, nodeId:zwaveHubNodeId),
    zwave.associationV2.associationSet(groupingIdentifier:2, nodeId:zwaveHubNodeId),
    zwave.configurationV1.configurationSet(parameterNumber: 3, size: 1, scaledConfigurationValue: settingInt("param3", 1)),
    zwave.configurationV1.configurationSet(parameterNumber: 20, size: 1, scaledConfigurationValue: settingInt("param20", 0)),
    zwave.configurationV1.configurationSet(parameterNumber: 80, size: 1, scaledConfigurationValue: settingInt("param80", 2)),
    zwave.configurationV1.configurationSet(parameterNumber: 90, size: 1, scaledConfigurationValue: 1),
    zwave.configurationV1.configurationSet(parameterNumber: 91, size: 2, scaledConfigurationValue: settingInt("param91", 50)),
    zwave.configurationV1.configurationSet(parameterNumber: 92, size: 1, scaledConfigurationValue: settingInt("param92", 10)),
    zwave.configurationV1.configurationSet(parameterNumber: 111, size: 4, scaledConfigurationValue: settingInt("param111", 300)),
    zwave.configurationV1.configurationSet(parameterNumber: 112, size: 4, scaledConfigurationValue: 0),
    zwave.configurationV1.configurationSet(parameterNumber: 113, size: 4, scaledConfigurationValue: 0),
    zwave.configurationV1.configurationSet(parameterNumber: 101, size: 4, scaledConfigurationValue: reportGroup),
    zwave.configurationV1.configurationSet(parameterNumber: 102, size: 4, scaledConfigurationValue: 0),
    zwave.configurationV1.configurationSet(parameterNumber: 103, size: 4, scaledConfigurationValue: 0)
  ], 500)

  if (!getDataValue("MSR")) {
    cmds = cmds + cmdSequence([
      zwave.versionV2.versionGet(),
      zwave.firmwareUpdateMdV2.firmwareMdGet(),
      zwave.manufacturerSpecificV2.manufacturerSpecificGet(),
    ], 100)
  }

  logger("debug", "configure() - cmds: ${cmds.inspect()}")

  cmds
}

def reset() {
  logger("debug", "reset()")

  sendEvent(name: "power", value: 0, unit: "W")
  sendEvent(name: "energy", value: 0, unit: "kWh")
  sendEvent(name: "amperage", value: 0, unit: "A")
  sendEvent(name: "voltage", value: 0, unit: "V")

  cmdSequence([
    zwave.meterV4.meterReset(),
    zwave.meterV4.meterGet(scale: 0),
    zwave.meterV4.meterGet(scale: 2),
    zwave.meterV4.meterGet(scale: 4),
    zwave.meterV4.meterGet(scale: 5)
  ])
}

def clearState() {
  logger("debug", "ClearStates() - Clearing device states")

  state.clear()

  if (state?.driverInfo == null) {
    state.driverInfo = [:]
  } else {
    state.driverInfo.clear()
  }

  if (state?.deviceInfo == null) {
    state.deviceInfo = [:]
  } else {
    state.deviceInfo.clear()
  }

  updateDataValue("MSR", "")
  installed()
}

def checkState() {
  logger("debug", "checkState()")

  cmdSequence([
    zwave.basicV1.basicGet()
  ], 200)
}

def parse(String description) {
  logger("debug", "parse() - description: ${description?.inspect()}")
  def result = []
  def cmd = zwave.parse(description, getCommandClassVersions())

  if (cmd) {
    result = zwaveEvent(cmd)
    logger("debug", "parse() - parsed to cmd: ${cmd?.inspect()} with result: ${result?.inspect()}")

  } else {
    logger("error", "parse() - Non-parsed - description: ${description?.inspect()}")
  }

  result
}

def handleMeterReport(cmd){
  logger("debug", "handleMeterReport() - cmd: ${cmd.inspect()}")

  def result = []
  List meterTypes = ["Unknown", "Electric", "Gas", "Water"]
  List electricNames = ["energy", "energy", "power", "count", "voltage", "amperage", "powerFactor", "unknown"]
  List electricUnits = ["kWh", "kVAh", "W", "pulses", "V", "A", "Power Factor", ""]
  List gasUnits = ["m^3", "ft^3", "", "pulses", ""]
  List waterUnits = ["m^3", "ft^3", "gal"]

  // ScaledPreviousMeterValue does not always contain a value
  def previousValue = cmd.scaledPreviousMeterValue ?: 0

  if (cmd.meterType == 1) { // electric
    logger("debug", "handleMeterReport() - deltaTime:${cmd.deltaTime} secs, meterType:${meterTypes[cmd.meterType]}, meterValue:${cmd.scaledMeterValue}, previousMeterValue:${cmd.scaledPreviousMeterValue}, scale:${electricNames[cmd.scale]}(${cmd.scale}), unit: ${electricUnits[cmd.scale]}, precision:${cmd.precision}, rateType:${cmd.rateType}")

    def map = [ name: electricNames[cmd.scale] ?: "electric", unit: electricUnits[cmd.scale], displayed: true]
    switch(cmd.scale) {
      case 0: //kWh
        previousValue = device.currentValue("energy") ?: cmd.scaledPreviousMeterValue ?: 0
        map.value = cmd.scaledMeterValue
      break;
      case 1: //kVAh - apparent energy, not kWh; don't let it overwrite the energy attribute
        logger("debug", "handleMeterReport() - Ignoring kVAh report: ${cmd.scaledMeterValue}")
        return result
      case 2: //Watts
        previousValue = device.currentValue("power") ?: cmd.scaledPreviousMeterValue ?: 0
        map.value = Math.round(cmd.scaledMeterValue)
      break;
      case 3: //pulses
        map.value = Math.round(cmd.scaledMeterValue)
      break;
      case 4: //Volts
        previousValue = device.currentValue("voltage") ?: cmd.scaledPreviousMeterValue ?: 0
        map.value = cmd.scaledMeterValue
      break;
      case 5: //Amps
        previousValue = device.currentValue("amperage") ?: cmd.scaledPreviousMeterValue ?: 0
        map.value = cmd.scaledMeterValue
      break;
      case 6: //Power Factor
      case 7: //Unknown
        logger("warn", "handleMeterReport() - Unknown type: ${cmd.scale}")
        map.value = cmd.scaledMeterValue
      break;
      default:
        logger("warn", "handleMeterReport() - Unknown type: ${cmd.scale}")
      break;
    }

    map.descriptionText = "${meterTypes[cmd.meterType]} ${map.name} is ${map?.value} ${map?.unit}"

    //Check if the value has changed my more than 5%, if so mark as a stateChange
    //map.isStateChange = ((cmd.scaledMeterValue - previousValue).abs() > (cmd.scaledMeterValue * 0.05))
    if (device.currentValue(map.name) != map.value) {
      if (logDescText) {
        log.info "${device.displayName} ${map.descriptionText}"
      } else if(map?.descriptionText) {
        logger("info", "${map.descriptionText}")
      }
    }
    result << createEvent(map)

  } else { // meter
    Map map = [name: "meter", descriptionText: cmd.toString()]
    result << createEvent(map)
    if (logDescText) {
      log.info "${device.displayName} ${map.descriptionText}"
    } else if(map?.descriptionText) {
      logger("info", "${map.descriptionText}")
    }
  }

  result
}

def zwaveEvent(hubitat.zwave.commands.associationv2.AssociationReport cmd) {
  logger("trace", "zwaveEvent(AssociationReport) - cmd: ${cmd.inspect()}")
  def result = []

  if (cmd.nodeId.any { it == zwaveHubNodeId }) {
    logger("info", "Is associated in group ${cmd.groupingIdentifier}")
  } else if (cmd.groupingIdentifier == 1) {
    logger("info", "Associating in group ${cmd.groupingIdentifier}")
    result << response(zwave.associationV2.associationSet(groupingIdentifier:cmd.groupingIdentifier, nodeId:zwaveHubNodeId))
  }
  result
}

def zwaveEvent(hubitat.zwave.commands.configurationv2.ConfigurationReport cmd) {
  logger("trace", "zwaveEvent(ConfigurationReport) - cmd: ${cmd.inspect()}")
  []
}

def zwaveEvent(hubitat.zwave.commands.deviceresetlocallyv1.DeviceResetLocallyNotification cmd) {
  logger("trace", "zwaveEvent(DeviceResetLocallyNotification) - cmd: ${cmd.inspect()}")
  logger("warn", "Has reset itself")
  []
}

def zwaveEvent(hubitat.zwave.commands.meterv4.MeterReport cmd) {
  logger("trace", "zwaveEvent(MeterReport) - cmd: ${cmd.inspect()}")
  handleMeterReport(cmd)
}

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd) {
  logger("trace", "zwaveEvent(BasicReport) - cmd: ${cmd.inspect()}")
  setSwitchEvent(cmd)
}

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicSet cmd) {
  logger("trace", "zwaveEvent(BasicSet) - cmd: ${cmd.inspect()}")
  setSwitchEvent(cmd)
}

private setSwitchEvent(hubitat.zwave.Command cmd) {
  logger("debug", "setSwitchEvent(Command) - cmd: ${cmd.inspect()}")
  def result = []

  String cv = device.currentValue("switch")
  String value = (cmd.value ? "on" : "off")
  boolean isStateChange = (cv?.toString() != value) ? true : false

  if (isStateChange) {
    if (logDescText) {
      log.info "${device.displayName} Was turned ${value}"
    } else {
      logger("info", "Was turned ${value}")
    }
  }

  result << createEvent(name: "switch", value: value, descriptionText: "Was turned ${value}")
  result
}

def zwaveEvent(hubitat.zwave.commands.switchbinaryv1.SwitchBinaryReport cmd) {
  logger("trace", "zwaveEvent(SwitchBinaryReport) - cmd: ${cmd.inspect()}")
  setSwitchEvent(cmd)
}

def zwaveEvent(hubitat.zwave.commands.hailv1.Hail hail) {
  // Not named cmd: a parameter called cmd would shadow the cmd() method below
  logger("trace", "zwaveEvent(Hail) - cmd: ${hail.inspect()}")
  [response(cmd(zwave.basicV1.basicGet()))]
}

def zwaveEvent(hubitat.zwave.commands.sensormultilevelv5.SensorMultilevelReport cmd) {
  logger("trace", "zwaveEvent(SensorMultilevelReport) - cmd: ${cmd.inspect()}")
  def result = []
  Map map = [:]

  switch (cmd.sensorType) {
    case 1:
      map.name = "temperature"
      map.value = convertTemperatureIfNeeded(cmd.scaledSensorValue, cmd.scale == 1 ? "F" : "C", cmd.precision)
      map.unit = "°" + getTemperatureScale()
      map.descriptionText = "Temperature is ${map.value} ${map.unit}"
      map.displayed = true
    break
    default:
      logger("warn", "zwaveEvent(SensorMultilevelReport) - Unknown sensorType - cmd: ${cmd.inspect()}")
    break;
  }

  if (device.currentValue(map.name) != map.value) {
    if (logDescText && map?.descriptionText) {
      log.info "${device.displayName} ${map.descriptionText}"
    } else if(map?.descriptionText) {
      logger("info", "${map.descriptionText}")
    }
  }
  result << createEvent(map)
  result
}

def zwaveEvent(hubitat.zwave.commands.powerlevelv1.PowerlevelReport cmd) {
  logger("trace", "zwaveEvent(PowerlevelReport) - cmd: ${cmd.inspect()}")

  String power = (cmd.powerLevel > 0) ? "-${cmd.powerLevel}dBm" : "NormalPower"
  logger("debug", "Powerlevel Report: Power: ${power}, Timeout: ${cmd.timeout}")
  []
}

void zwaveEvent(hubitat.zwave.commands.versionv1.VersionReport cmd) {
  logger("trace", "zwaveEvent(VersionReport) - cmd: ${cmd.inspect()}")

  if(cmd.applicationVersion != null && cmd.applicationSubVersion != null) {
    String firmware = versionString(cmd.applicationVersion, cmd.applicationSubVersion)
    updateDataValue("firmware", firmware)
    state.deviceInfo['firmwareVersion'] = firmware
    state.deviceInfo['protocolVersion'] = versionString(cmd.zWaveProtocolVersion, cmd.zWaveProtocolSubVersion)

  } else if(cmd.firmware0Version != null && cmd.firmware0SubVersion != null) {
    String firmware = versionString(cmd.firmware0Version, cmd.firmware0SubVersion)
    updateDataValue("firmware", firmware)
    state.deviceInfo['firmwareVersion'] = firmware
    state.deviceInfo['protocolVersion'] = versionString(cmd.zWaveProtocolVersion, cmd.zWaveProtocolSubVersion)
  }
  []
}

void zwaveEvent(hubitat.zwave.commands.versionv2.VersionReport cmd) {
  logger("trace", "zwaveEvent(VersionReport) - cmd: ${cmd.inspect()}")

  String firmware = versionString(cmd.firmware0Version, cmd.firmware0SubVersion)
  updateDataValue("firmware", firmware)
  state.deviceInfo['firmwareVersion'] = firmware
  state.deviceInfo['protocolVersion'] = versionString(cmd.zWaveProtocolVersion, cmd.zWaveProtocolSubVersion)
  state.deviceInfo['hardwareVersion'] = cmd.hardwareVersion

  if (cmd.firmwareTargets > 0) {
    cmd.targetVersions.each { target ->
      state.deviceInfo["firmware${target.target}Version"] = versionString(target.version, target.subVersion)
    }
  }
  []
}

void zwaveEvent(hubitat.zwave.commands.versionv3.VersionReport cmd) {
  logger("trace", "zwaveEvent(VersionReport) - cmd: ${cmd.inspect()}")

  String firmware = versionString(cmd.firmware0Version, cmd.firmware0SubVersion)
  updateDataValue("firmware", firmware)
  state.deviceInfo['firmwareVersion'] = firmware
  state.deviceInfo['protocolVersion'] = versionString(cmd.zWaveProtocolVersion, cmd.zWaveProtocolSubVersion)
  state.deviceInfo['hardwareVersion'] = cmd.hardwareVersion

  if (cmd.firmwareTargets > 0) {
    cmd.targetVersions.each { target ->
      state.deviceInfo["firmware${target.target}Version"] = versionString(target.version, target.subVersion)
    }
  }
  []
}

def zwaveEvent(hubitat.zwave.commands.versionv1.VersionCommandClassReport cmd) {
  logger("trace", "zwaveEvent(VersionCommandClassReport) - cmd: ${cmd.inspect()}")

  state.deviceInfo['commandClassVersion'] = "${cmd.commandClassVersion}"
  state.deviceInfo['requestedCommandClass'] = "${cmd.requestedCommandClass}"
  []
}

def zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.DeviceSpecificReport cmd) {
  logger("trace", "zwaveEvent(DeviceSpecificReport) - cmd: ${cmd.inspect()}")

  state.deviceInfo['deviceIdData'] = "${cmd.deviceIdData}"
  state.deviceInfo['deviceIdDataFormat'] = "${cmd.deviceIdDataFormat}"
  state.deviceInfo['deviceIdDataLengthIndicator'] = "l${cmd.deviceIdDataLengthIndicator}"
  state.deviceInfo['deviceIdType'] = "${cmd.deviceIdType}"
  []
}

def zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd) {
  logger("trace", "zwaveEvent(ManufacturerSpecificReport) - cmd: ${cmd.inspect()}")

  state.deviceInfo['manufacturerId'] = "${cmd.manufacturerId}"
  state.deviceInfo['productId'] = "${cmd.productId}"
  state.deviceInfo['productTypeId'] = "${cmd.productTypeId}"

  String msr = String.format("%04X-%04X-%04X", cmd.manufacturerId, cmd.productTypeId, cmd.productId)
  updateDataValue("MSR", msr)
  if (cmd?.manufacturerName && cmd?.manufacturerName != "") {
    updateDataValue("manufacturer", cmd.manufacturerName)
    state.deviceInfo['manufacturerName'] = "${cmd.manufacturerName}"
  } else if (cmd?.manufacturerId != "") {
    updateDataValue("manufacturer", cmd?.manufacturerId?.toString())
  }
  []
}

def zwaveEvent(hubitat.zwave.commands.firmwareupdatemdv2.FirmwareMdReport cmd) {
  logger("trace", "zwaveEvent(FirmwareMdReport) - cmd: ${cmd.inspect()}")

  state.deviceInfo['firmwareChecksum'] = "${cmd.checksum}"
  state.deviceInfo['firmwareId'] = "${cmd.firmwareId}"
  []
}

def zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
  logger("trace", "zwaveEvent(SecurityMessageEncapsulation) - cmd: ${cmd.inspect()}")

  def encapsulatedCommand = cmd.encapsulatedCommand(getCommandClassVersions())
  if (encapsulatedCommand) {
    logger("trace", "zwaveEvent(SecurityMessageEncapsulation) - encapsulatedCommand: ${encapsulatedCommand}")
    zwaveEvent(encapsulatedCommand)
  } else {
    logger("warn", "zwaveEvent(SecurityMessageEncapsulation) - Unable to extract Secure command from: ${cmd.inspect()}")
    []
  }
}

def zwaveEvent(hubitat.zwave.commands.crc16encapv1.Crc16Encap cmd) {
  logger("trace", "zwaveEvent(Crc16Encap) - cmd: ${cmd.inspect()}")

  def encapsulatedCommand = zwave.getCommand(cmd.commandClass, cmd.command, cmd.data)
  if (encapsulatedCommand) {
    logger("trace", "zwaveEvent(Crc16Encap) - encapsulatedCommand: ${encapsulatedCommand}")
    zwaveEvent(encapsulatedCommand)
  } else {
    logger("warn", "zwaveEvent(Crc16Encap) - Unable to extract CRC16 command from: ${cmd.inspect()}")
    []
  }
}

def zwaveEvent(hubitat.zwave.Command cmd) {
  logger("warn", "zwaveEvent(Command) - Unhandled - cmd: ${cmd.inspect()}")
  []
}

private String cmd(hubitat.zwave.Command cmd) {
  logger("trace", "cmd(Command) - cmd: ${cmd.inspect()}")
  // The platform knows how the device was included (none/S0/S2) and encapsulates to match
  zwaveSecureEncap(cmd)
}

private cmdSequence(Collection commands, Integer delayBetweenArgs=250) {
  logger("trace", "cmdSequence(Command) - commands: ${commands.inspect()} delayBetweenArgs: ${delayBetweenArgs}")
  delayBetween(commands.collect{ cmd(it) }, delayBetweenArgs)
}

private String versionString(major, minor) {
  "${major}.${minor.toString().padLeft(2,'0')}"
}

// Preferences have no values until they're saved once, so fall back to the declared defaults
private Integer settingInt(String name, Integer defaultValue) {
  settings[name] != null ? settings[name].toInteger() : defaultValue
}

private Boolean settingBool(String name, Boolean defaultValue) {
  settings[name] != null ? settings[name].toString() == "true" : defaultValue
}

private getCommandClassVersions() {
  return [0x25: 1, // COMMAND_CLASS_SWITCH_BINARY
          0x32: 4, // COMMAND_CLASS_METER
          0x31: 5, // COMMAND_CLASS_SENSOR_MULTILEVEL
          0x27: 1, // COMMAND_CLASS_SWITCH_ALL
          0x2C: 1, // COMMAND_CLASS_SCENE_ACTUATOR_CONF
          0x2B: 1, // COMMAND_CLASS_SCENE_ACTIVATION
          0x70: 2, // COMMAND_CLASS_CONFIGURATION_V2 (Secure)
          0x85: 2, // COMMAND_CLASS_ASSOCIATION_V2 (Secure)
          0x59: 1, // COMMAND_CLASS_ASSOCIATION_GRP_INFO (Secure)
          0x56: 1, // COMMAND_CLASS_CRC_16_ENCAP
          0x72: 2, // COMMAND_CLASS_MANUFACTURER_SPECIFIC (Insecure)
          0x86: 2, // COMMAND_CLASS_VERSION (Insecure)
          0x7A: 2, // COMMAND_CLASS_FIRMWARE_UPDATE_MD (Insecure)
          0x73: 1, // COMMAND_CLASS_POWERLEVEL (Insecure)
          0x98: 1  // COMMAND_CLASS_SECURITY (Secure)
  ]
}

/**
 * @param level Level to log at, see LOG_LEVELS for options
 * @param msg Message to log
 */
private logger(level, msg) {
  if (level && msg) {
    Integer levelIdx = LOG_LEVELS.indexOf(level)
    Integer setLevelIdx = LOG_LEVELS.indexOf(logLevel)
    if (setLevelIdx < 0) {
      setLevelIdx = LOG_LEVELS.indexOf(DEFAULT_LOG_LEVEL)
    }
    if (levelIdx <= setLevelIdx) {
      log."${level}" "${device.displayName} ${msg}"
    }
  }
}
