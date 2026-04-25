/*
 * Netatmo Weather Outdoor Module - Hubitat Driver
 * Version: 0.1.0
 *
 * Copyright 2026 Brent Rossow
 * SPDX-License-Identifier: Apache-2.0
 *
 * Driver for normalized data supplied by the Netatmo Weather Station Connect parent app.
 */

metadata {
    definition(
        name: "Netatmo Weather Outdoor Module",
        namespace: "brossow",
        author: "Brent Rossow",
        description: "Netatmo Weather Station outdoor module"
    ) {
        capability "Sensor"
        capability "Refresh"
        capability "Battery"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"

        attribute "rfStatus", "number"
        attribute "reachable", "string"
        attribute "lastSeen", "string"
        attribute "measurementTime", "string"
        attribute "temperatureTrend", "string"
        attribute "stationName", "string"
        attribute "moduleName", "string"
        attribute "lastUpdated", "string"
    }
}

def installed() {
    log.info "Netatmo Weather Outdoor Module installed"
}

def updated() {
    log.info "Netatmo Weather Outdoor Module updated"
}

def refresh() {
    try {
        if (parent) {
            parent.refreshChild(device.deviceNetworkId)
        } else {
            log.warn "Netatmo Weather Outdoor Module refresh skipped: parent app is unavailable"
        }
    } catch (Exception e) {
        log.warn "Netatmo Weather Outdoor Module refresh failed: ${e?.message ?: e}"
    }
}

def updatedFromParent(Map data) {
    if (!(data instanceof Map)) {
        log.warn "Netatmo Weather Outdoor Module update skipped: parent data was missing or invalid"
        return
    }

    Map dashboard = data.dashboard instanceof Map ? (Map)data.dashboard : [:]
    Map metadata = data.metadata instanceof Map ? (Map)data.metadata : [:]
    Map units = data.units instanceof Map ? (Map)data.units : [:]

    sendEventIfPresent("temperature", dashboard.temperature, units.temperature ?: temperatureUnit())
    sendEventIfPresent("humidity", dashboard.humidity, "%")
    sendEventIfPresent("battery", metadata.batteryPercent, "%")
    sendEventIfPresent("rfStatus", metadata.rfStatus)
    sendEventIfPresent("temperatureTrend", dashboard.tempTrend)
    sendEventIfPresent("reachable", data.reachable == null ? null : data.reachable.toString())
    sendEventIfPresent("stationName", data.stationName)
    sendEventIfPresent("moduleName", data.moduleName)

    String lastSeenValue = formatEpochSeconds(data.lastSeen)
    sendEventIfPresent("lastSeen", lastSeenValue)
    sendEventIfPresent("measurementTime", data.measurementTime)
    sendEvent(name: "lastUpdated", value: formatNow())
}

private void sendEventIfPresent(String name, Object value, String unit = null) {
    if (value == null) {
        return
    }

    Map event = [name: name, value: value]
    if (unit) {
        event.unit = unit
    }
    sendEvent(event)
}

private String temperatureUnit() {
    return location?.temperatureScale ?: "F"
}

private String formatEpochSeconds(Object value) {
    Long seconds = safeLong(value)
    if (seconds == null) {
        return null
    }

    try {
        return new Date(seconds * 1000L).format("yyyy-MM-dd HH:mm:ss z", location?.timeZone ?: TimeZone.getDefault())
    } catch (Exception e) {
        log.warn "Could not format Netatmo lastSeen value ${value}: ${e?.message ?: e}"
        return value as String
    }
}

private String formatNow() {
    return new Date().format("yyyy-MM-dd HH:mm:ss z", location?.timeZone ?: TimeZone.getDefault())
}

private Long safeLong(Object value) {
    try {
        return value == null ? null : value as Long
    } catch (Exception e) {
        log.warn "Could not convert Netatmo value to Long: ${value}"
        return null
    }
}
