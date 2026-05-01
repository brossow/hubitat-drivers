#!/usr/bin/env python3
"""
Bambu Lab → Hubitat bridge (local MQTT).

Connects directly to the printer's on-board MQTT broker on the LAN,
parses status payloads, and POSTs parsed state to a Hubitat app endpoint.

The local broker is always available on the LAN regardless of whether
"LAN Only Mode" is enabled on the printer. Cloud access (Handy app) is unaffected.

Required environment variables:
    BAMBU_IP          Printer's local IP address
    BAMBU_ACCESS_CODE 8-character access code (printer touchscreen → Settings → WLAN)
    BAMBU_SERIAL      Printer serial number (Bambu Studio ▸ Device, or printer touchscreen)
    HUBITAT_URL       Full Hubitat endpoint URL — copy from the Bambu Bridge app page after install

Optional environment variables:
    RECONNECT_DELAY   Seconds between reconnect attempts (default: 30)
    LOG_LEVEL         DEBUG | INFO (default) | WARNING | ERROR
"""

import json
import logging
import os
import pathlib
import signal
import ssl
import threading
from datetime import datetime, timezone

import paho.mqtt.client as mqtt
import requests

logging.basicConfig(
    level=os.environ.get("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s %(levelname)-8s %(message)s",
    datefmt="%Y-%m-%dT%H:%M:%S",
)
log = logging.getLogger(__name__)

# ── Config ───────────────────────────────────────────────────────────────────

BAMBU_IP          = os.environ["BAMBU_IP"]
BAMBU_ACCESS_CODE = os.environ["BAMBU_ACCESS_CODE"]
BAMBU_SERIAL      = os.environ["BAMBU_SERIAL"]
HUBITAT_URL       = os.environ["HUBITAT_URL"]
RECONNECT_DELAY   = int(os.environ.get("RECONNECT_DELAY", "30"))

MQTT_PORT    = 8883
REPORT_TOPIC = f"device/{BAMBU_SERIAL}/report"

SPEED_LABELS = {1: "Quiet", 2: "Standard", 3: "Sport", 4: "Ludicrous"}

# ── Payload parsing ───────────────────────────────────────────────────────────

def _ams_summary(ams: dict) -> str:
    """Compact summary of AMS tray contents, e.g. 'A0T0:PLA/FF0000/75%, A0T1:PETG/FFFFFF/?'"""
    parts = []
    for unit in ams.get("ams", []):
        uid = unit.get("id", "?")
        for tray in unit.get("tray", []):
            tid    = tray.get("id", "?")
            mtype  = tray.get("tray_type") or "—"
            color  = (tray.get("tray_color") or "")[:6]
            remain = tray.get("remain", -1)
            pct    = f"{remain}%" if isinstance(remain, int) and remain >= 0 else "?"
            parts.append(f"A{uid}T{tid}:{mtype}/{color}/{pct}")
    return ", ".join(parts)


def _ams_tray_now(ams: dict) -> int:
    """Return the globally-addressed active tray index (unit*4 + slot), or 255 if none."""
    try:
        val = ams.get("tray_now", "255")
        return int(val)
    except (ValueError, TypeError):
        return 255


def parse_report(payload: dict) -> dict | None:
    """
    Extract actionable fields from a Bambu push_status report.
    Returns None if the payload contains no printer state (e.g. pure AMS/info messages).

    Camera note: payload["print"]["ipcam"]["rtsp_url"] carries the RTSP stream URL
    (format: rtsps://bblp:{access_code}@{printer_ip}:322/streaming/live/1).
    Exposed as the cameraUrl attribute — LAN-reachable without any extra config.
    """
    p = payload.get("print")
    if not p:
        return None

    state = p.get("gcode_state")
    if not state:
        return None

    ams_raw = p.get("ams") or {}
    ipcam   = p.get("ipcam") or {}

    # Chamber temperature: newer firmware packs current+target into device.ctc.info.temp
    # (low 16 bits = current °C, high 16 bits = target °C). The device key may appear
    # at the top level of the payload OR nested inside print — check both. Older firmware
    # uses the flat chamber_temper float inside print.
    _device     = payload.get("device") or p.get("device") or {}
    _ctc_packed = _device.get("ctc", {}).get("info", {}).get("temp")
    chamber_temp = (_ctc_packed & 0xFFFF) if _ctc_packed is not None else float(p.get("chamber_temper", 0))

    return {
        "printerState":     state,                                              # IDLE | PREPARE | RUNNING | PAUSE | FINISH | FAILED
        "printFile":        p.get("subtask_name") or p.get("gcode_file") or "",
        "printProgress":    int(p.get("mc_percent", 0)),                        # 0–100
        "remainingTime":    int(p.get("mc_remaining_time", 0)),                 # minutes
        "currentLayer":     int(p.get("layer_num", 0)),
        "totalLayers":      int(p.get("total_layer_num", 0)),
        "nozzleTemp":       round(float(p.get("nozzle_temper", 0)), 1),         # °C
        "nozzleTargetTemp": round(float(p.get("nozzle_target_temper", 0)), 1),
        "bedTemp":          round(float(p.get("bed_temper", 0)), 1),
        "bedTargetTemp":    round(float(p.get("bed_target_temper", 0)), 1),
        "chamberTemp":      round(float(chamber_temp), 1),
        "speedLevel":       SPEED_LABELS.get(int(p.get("spd_lvl", 2)), "Standard"),
        "speedMagnitude":   int(p.get("spd_mag", 100)),                         # %
        "printError":       str(p.get("mc_print_error_code", "0")),
        "wifiSignal":       str(p.get("wifi_signal", "")),
        "amsSummary":       _ams_summary(ams_raw),
        "amsTrayNow":       _ams_tray_now(ams_raw),                             # globally-addressed active tray (255 = none/external)
        "cameraUrl":        ipcam.get("rtsp_url", ""),                          # for future camera integration
        "lastUpdate":       datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }

# ── Hubitat push ──────────────────────────────────────────────────────────────

def push_to_hubitat(data: dict) -> None:
    try:
        resp = requests.post(HUBITAT_URL, json=data, timeout=10)
        resp.raise_for_status()
        log.debug(
            "→ Hubitat OK  state=%-8s  progress=%s%%  nozzle=%.1f°C  bed=%.1f°C",
            data.get("printerState"), data.get("printProgress"),
            data.get("nozzleTemp", 0), data.get("bedTemp", 0),
        )
    except requests.RequestException as exc:
        log.error("Hubitat push failed: %s", exc)

# ── MQTT bridge ───────────────────────────────────────────────────────────────

class BambuBridge:
    def __init__(self):
        self._stop   = threading.Event()
        self._client = None

    def _make_client(self) -> mqtt.Client:
        client = mqtt.Client(
            client_id=f"bambu_hubitat_{BAMBU_SERIAL[-6:]}",
            protocol=mqtt.MQTTv311,
            clean_session=True,
        )
        client.username_pw_set("bblp", BAMBU_ACCESS_CODE)

        # Bambu's broker uses a private CA; disable verification (same as BLLED and all community clients)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode    = ssl.CERT_NONE
        client.tls_set_context(ctx)

        client.on_connect    = self._on_connect
        client.on_disconnect = self._on_disconnect
        client.on_message    = self._on_message
        return client

    def _on_connect(self, client, userdata, flags, rc):
        if rc == 0:
            log.info("Connected to %s; subscribed to %s", BAMBU_IP, REPORT_TOPIC)
            client.subscribe(REPORT_TOPIC, qos=0)
        else:
            log.error("MQTT connect failed rc=%d (rc=4 = bad access code); will retry", rc)
            client.disconnect()

    def _on_disconnect(self, client, userdata, rc):
        if rc != 0:
            log.warning("Unexpected MQTT disconnect rc=%d", rc)

    def _on_message(self, client, userdata, msg):
        try:
            payload = json.loads(msg.payload)
            data    = parse_report(payload)
            if data:
                push_to_hubitat(data)
                pathlib.Path("/tmp/bambu-healthy").touch()
        except Exception as exc:
            log.error("Message processing error: %s", exc)

    def run(self):
        while not self._stop.is_set():
            try:
                self._client = self._make_client()
                self._client.connect(BAMBU_IP, MQTT_PORT, keepalive=60)
                self._client.loop_forever()
            except KeyboardInterrupt:
                break
            except Exception as exc:
                log.error("Bridge error: %s", exc)

            if self._stop.is_set():
                break
            log.info("Reconnecting in %ds…", RECONNECT_DELAY)
            self._stop.wait(RECONNECT_DELAY)

    def stop(self):
        self._stop.set()
        if self._client:
            self._client.disconnect()


if __name__ == "__main__":
    bridge = BambuBridge()
    signal.signal(signal.SIGTERM, lambda *_: bridge.stop())
    try:
        bridge.run()
    except KeyboardInterrupt:
        bridge.stop()
