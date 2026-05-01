# Running the bridge: platform setup guide

The `bambu_bridge.py` script is a small Python process that needs to run continuously on something that's always on and can reach both the printer and your Hubitat hub over your local network. This guide covers the most common options.

---

## Choosing a platform

| Platform | Effort | Notes |
|---|---|---|
| Docker (any host) | Low | Recommended — handles restarts, isolation, and updates cleanly |
| Synology NAS | Low | Docker via Container Manager; see main README |
| QNAP NAS | Low | Docker via Container Station; similar to Synology |
| Unraid | Low | Docker via the Unraid GUI |
| Raspberry Pi | Medium | Direct Python; systemd for persistence |
| Linux server / VM | Medium | systemd; same as Raspberry Pi |
| Windows PC | Medium | NSSM (recommended) or Task Scheduler |
| macOS | Medium | launchd; see macOS section below |
| Home Assistant OS | Not recommended | Locked-down OS; no package manager access. Consider running the bridge on a separate device instead. |

---

## Python requirements (all non-Docker platforms)

The bridge requires **Python 3.9 or later**. Check your version:

```bash
python3 --version
```

### Using a virtual environment (recommended)

A virtual environment isolates the bridge's dependencies from the rest of your system. This is the best practice for any platform.

```bash
cd /path/to/hubitat-drivers/bambu-lab/bridge

# Create the virtual environment
python3 -m venv .venv

# Activate it (Linux / macOS)
source .venv/bin/activate

# Activate it (Windows PowerShell)
.\.venv\Scripts\Activate.ps1

# Install dependencies
pip install -r requirements.txt
```

After activating the venv, run the bridge as:

```bash
# With env vars set in the shell
python bambu_bridge.py

# Or with an env file loaded manually
set -a && source .env && set +a && python bambu_bridge.py   # bash/zsh
```

When using a service manager (systemd, launchd, NSSM), point `ExecStart` / the executable path at the venv's Python binary directly — the venv doesn't need to be "activated" first.

---

## Raspberry Pi

A Pi 3 or later is more than sufficient. A Pi Zero 2 W will also work.

### 1. Install Python and dependencies

```bash
sudo apt update && sudo apt install -y python3 python3-pip python3-venv

cd /home/pi   # or wherever you want to keep it
git clone https://github.com/brossow/hubitat-drivers.git
cd hubitat-drivers/bambu-lab/bridge

python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

### 2. Create your `.env` file

```bash
cp .env.example .env
nano .env   # fill in your values
```

### 3. Test it manually first

```bash
source .venv/bin/activate
set -a && source .env && set +a
python bambu_bridge.py
```

You should see `Connected to <printer IP>` in the output. Press Ctrl-C to stop.

### 4. Set up as a systemd service

Create `/etc/systemd/system/bambu-bridge.service`:

```ini
[Unit]
Description=Bambu Lab → Hubitat bridge
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=pi
WorkingDirectory=/home/pi/hubitat-drivers/bambu-lab/bridge
EnvironmentFile=/home/pi/hubitat-drivers/bambu-lab/bridge/.env
ExecStart=/home/pi/hubitat-drivers/bambu-lab/bridge/.venv/bin/python bambu_bridge.py
Restart=always
RestartSec=30

[Install]
WantedBy=multi-user.target
```

Enable and start:

```bash
sudo systemctl daemon-reload
sudo systemctl enable bambu-bridge
sudo systemctl start bambu-bridge

# Check status
sudo systemctl status bambu-bridge

# View live logs
sudo journalctl -u bambu-bridge -f
```

The service starts automatically on boot and restarts if the script crashes or loses the MQTT connection.

---

## Windows

### Option A: NSSM (recommended)

[NSSM](https://nssm.cc) (Non-Sucking Service Manager) wraps any executable as a proper Windows service with automatic restart on failure.

**1. Install Python** from [python.org](https://python.org) — check "Add Python to PATH" during installation.

**2. Set up the bridge:**

```powershell
cd C:\hubitat-drivers\bambu-lab\bridge
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

**3. Create a launcher script** — `C:\hubitat-drivers\bambu-lab\bridge\start.bat`:

```bat
@echo off
set BAMBU_IP=10.0.1.x
set BAMBU_ACCESS_CODE=xxxxxxxx
set BAMBU_SERIAL=01P00C000000000
set HUBITAT_URL=http://...
C:\hubitat-drivers\bambu-lab\bridge\.venv\Scripts\python.exe C:\hubitat-drivers\bambu-lab\bridge\bambu_bridge.py
```

**4. Install NSSM** — download from [nssm.cc/download](https://nssm.cc/download), extract, and run from an Administrator PowerShell:

```powershell
nssm install BambuBridge "C:\hubitat-drivers\bambu-lab\bridge\start.bat"
nssm set BambuBridge DisplayName "Bambu Lab Bridge"
nssm set BambuBridge Description "Bambu Lab printer → Hubitat MQTT bridge"
nssm set BambuBridge Start SERVICE_AUTO_START
nssm start BambuBridge
```

To view or edit the service later: `nssm edit BambuBridge`

To check status: `nssm status BambuBridge`

**5. Logs** are written to the Windows Application event log by default. You can redirect them in NSSM's I/O tab to a log file.

---

### Option B: Task Scheduler

Less robust than NSSM (no restart-on-failure), but requires no third-party tools.

**1–2.** Same Python and venv setup as Option A.

**3.** Open **Task Scheduler** → **Create Task**:

- **General tab:** name it "Bambu Bridge", check "Run whether user is logged on or not" and "Run with highest privileges"
- **Triggers tab:** New → At startup → OK
- **Actions tab:** New → Start a program
  - Program: `C:\hubitat-drivers\bambu-lab\bridge\.venv\Scripts\python.exe`
  - Arguments: `C:\hubitat-drivers\bambu-lab\bridge\bambu_bridge.py`
  - Start in: `C:\hubitat-drivers\bambu-lab\bridge`
- **Settings tab:** check "If the task fails, restart every 1 minute" (up to 3 times or indefinitely)

**4.** Set environment variables — Task Scheduler doesn't have a built-in env file mechanism. Either:
- Hardcode them in the launcher `.bat` script as in Option A and call the `.bat` from the task, or
- Set them as System environment variables in Control Panel → System → Advanced → Environment Variables

---

## macOS

The bridge can run as a persistent background service using **launchd**. This is a good option if you have a Mac that's always on and want to avoid running Docker.

### 1. Set up Python and dependencies

macOS includes Python 3, but using [Homebrew](https://brew.sh)'s Python is recommended for a clean, manageable install:

```bash
brew install python3
```

Clone the repo and set up a virtual environment:

```bash
cd ~/Documents   # or wherever you'd like to keep it
git clone https://github.com/brossow/hubitat-drivers.git
cd hubitat-drivers/bambu-lab/bridge

python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

### 2. Create your `.env` file

```bash
cp .env.example .env
nano .env   # fill in your values
```

### 3. Test it manually first

```bash
source .venv/bin/activate
set -a && source .env && set +a
python bambu_bridge.py
```

You should see `Connected to <printer IP>` in the output. Press Ctrl-C to stop.

### 4. Install as a launchd service

Create `~/Library/LaunchAgents/com.bambu.bridge.plist`. Replace the paths and environment variable values with your own:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.bambu.bridge</string>
    <key>ProgramArguments</key>
    <array>
        <string>/Users/yourname/Documents/hubitat-drivers/bambu-lab/bridge/.venv/bin/python</string>
        <string>/Users/yourname/Documents/hubitat-drivers/bambu-lab/bridge/bambu_bridge.py</string>
    </array>
    <key>EnvironmentVariables</key>
    <dict>
        <key>BAMBU_IP</key>
        <string>10.0.1.x</string>
        <key>BAMBU_ACCESS_CODE</key>
        <string>xxxxxxxx</string>
        <key>BAMBU_SERIAL</key>
        <string>01P00C000000000</string>
        <key>HUBITAT_URL</key>
        <string>http://192.168.1.x/apps/api/APP_ID/update?access_token=YOUR_TOKEN</string>
    </dict>
    <key>WorkingDirectory</key>
    <string>/Users/yourname/Documents/hubitat-drivers/bambu-lab/bridge</string>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>/tmp/bambu-bridge.log</string>
    <key>StandardErrorPath</key>
    <string>/tmp/bambu-bridge.log</string>
</dict>
</plist>
```

Load and start the service:

```bash
launchctl load ~/Library/LaunchAgents/com.bambu.bridge.plist
```

Check that it's running:

```bash
launchctl list | grep bambu
```

A PID in the first column means it's running. View live logs:

```bash
tail -f /tmp/bambu-bridge.log
```

To stop and remove the service:

```bash
launchctl unload ~/Library/LaunchAgents/com.bambu.bridge.plist
```

To restart after changing the `.env` or plist values:

```bash
launchctl unload ~/Library/LaunchAgents/com.bambu.bridge.plist
launchctl load ~/Library/LaunchAgents/com.bambu.bridge.plist
```

> **Note:** `LaunchAgents` run only while the user is logged in. For most home users this is sufficient — the service starts automatically at login. If you need it to run without a logged-in user, it can instead be installed as a `LaunchDaemon` in `/Library/LaunchDaemons/` (requires `sudo`), but that setup is outside the scope of this guide.

---

## QNAP NAS

QNAP's **Container Station** app provides a Docker environment very similar to Synology's Container Manager.

### 1. Install Container Station

Open the QNAP App Center and install **Container Station** if not already installed.

### 2. Copy files to the NAS

Use QNAP File Station or `scp` to copy the `bridge/` folder to your NAS — for example, to `/share/Container/BambuBridge/`.

### 3. Create your `.env` file

In File Station, copy `.env.example` to `.env` in the same folder and edit it with your values.

### 4. Deploy via Container Station

**Option A — Docker Compose (Container Station 3.x):**

1. Open Container Station → **Applications** → **Create**
2. Select the folder containing your `docker-compose.yml`
3. Click **Deploy**

**Option B — via SSH:**

Enable SSH in QNAP Control Panel → Network & File Services → Telnet/SSH, then:

```bash
ssh admin@<nas-ip>
cd /share/Container/BambuBridge
docker compose up -d
```

Logs:

```bash
docker compose logs -f
```

---

## Unraid

### Option A: Docker via the Unraid UI (recommended)

**1.** In Unraid, go to **Docker** tab → **Add Container**

**2.** Fill in:
- **Name:** `bambu-bridge`
- **Repository:** leave blank (we'll build locally) — or use a pre-built image if one becomes available
- **Network Type:** Bridge

Because the bridge needs a custom build, the easiest approach on Unraid is to use **Docker Compose** via the terminal:

```bash
# SSH into Unraid
ssh root@<unraid-ip>

# Copy bridge files to your appdata share
mkdir -p /mnt/user/appdata/bambu-bridge
# (copy files from your Mac/PC using scp or the Unraid share)

cd /mnt/user/appdata/bambu-bridge
docker compose up -d
```

### Option B: Community Applications plugin

If a template is submitted to the Unraid Community Applications repository in the future, it will appear there. For now, the SSH + Docker Compose approach above is the path.

### Logs

```bash
docker logs bambu-bridge -f
```

Or view in the Unraid Docker tab by clicking the container icon.

---

## General Linux (any distribution)

The Raspberry Pi systemd guide above applies to any Linux system. Key differences for other distros:

- **Fedora/RHEL/CentOS:** replace `apt` with `dnf`, package names may differ slightly
- **Arch Linux:** `pacman -S python python-pip`, venv setup is the same
- **Alpine Linux:** `apk add python3 py3-pip`, use `rc-service`/`rc-update` instead of systemd
- **OpenWrt:** not recommended — Python package availability is limited and RAM is constrained

For any distro, the pattern is the same:
1. Install Python 3.9+
2. Create a venv, install requirements
3. Create an `.env` file
4. Set up a service using whatever init system the distro uses

---

## Updating the bridge

### Docker — if you used git clone

```bash
cd /path/to/hubitat-drivers/bambu-lab/bridge
git pull
sudo docker compose down && sudo docker compose up -d --build
```

### Docker — if you copied files manually (Synology, QNAP, Unraid, etc.)

Copy the updated bridge files into your deployment directory, then rebuild. Your `.env` file stays untouched.

**From a Mac or Linux machine:**

```bash
scp bridge/bambu_bridge.py bridge/Dockerfile bridge/docker-compose.yml bridge/requirements.txt \
    user@nas-host:/path/to/your/bridge/
```

**Or download directly on the NAS** (replace the URL with the raw file URL for the relevant file on GitHub):

```bash
curl -o bambu_bridge.py https://raw.githubusercontent.com/brossow/hubitat-drivers/main/bambu-lab/bridge/bambu_bridge.py
# repeat for Dockerfile, docker-compose.yml, requirements.txt if needed
```

Then rebuild:

```bash
sudo docker compose down && sudo docker compose up -d --build
```

> **Tip:** Cloning the repo directly onto your NAS avoids this manual step on future updates — `git pull` then rebuild. If the repo is private, authenticate with a [GitHub personal access token](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens) when prompted.

### Non-Docker (systemd)

```bash
cd /path/to/hubitat-drivers
git pull

# Reinstall dependencies if requirements.txt changed
source bridge/.venv/bin/activate
pip install -r bridge/requirements.txt

sudo systemctl restart bambu-bridge
```

### Non-Docker (macOS launchd)

```bash
cd /path/to/hubitat-drivers
git pull

source bridge/.venv/bin/activate
pip install -r bridge/requirements.txt

launchctl unload ~/Library/LaunchAgents/com.bambu.bridge.plist
launchctl load ~/Library/LaunchAgents/com.bambu.bridge.plist
```

### Non-Docker (Windows NSSM)

```powershell
git pull

.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt

nssm restart BambuBridge
```
