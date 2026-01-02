# Docker Integration Guide - P2P Video Streaming

This guide explains how to run the P2P Video Streaming application using Docker for headless backend peers.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    WINDOWS HOST                              │
│                                                              │
│  ┌──────────────────────────────────────────────┐           │
│  │  GUI Peer (MainFrame.java)                    │           │
│  │  - Swing GUI + VLC Player                     │           │
│  │  - Discovery: 255.255.255.255 or configured  │           │
│  │  - IP: Host network (192.168.x.x)            │           │
│  └──────────────────────────────────────────────┘           │
│                        ↓ ↑                                   │
│  ┌────────────────────────────────────────────────────────┐ │
│  │           Docker Bridge Network: 172.20.0.0/16         │ │
│  │                                                         │ │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐   │ │
│  │  │   Peer 1    │  │   Peer 2    │  │   Peer 3    │   │ │
│  │  │ 172.20.0.2  │  │ 172.20.0.3  │  │ 172.20.0.4  │   │ │
│  │  │ Headless    │  │ Headless    │  │ Headless    │   │ │
│  │  │ /videos_1   │  │ /videos_2   │  │ /videos_3   │   │ │
│  │  └─────────────┘  └─────────────┘  └─────────────┘   │ │
│  └────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

**Key Points:**
- **GUI runs on Windows host** (with VLC)
- **Docker containers run headless peers** (no GUI, no VLC)
- **Custom bridge network** allows peer-to-peer discovery
- **Subnet broadcast** (172.20.255.255) for Docker peer discovery

---

## Prerequisites

1. **Docker Desktop for Windows** installed and running
2. **Java 17** installed on Windows host (for GUI peer)
3. **VLC Media Player** installed on Windows host
4. **Maven** installed (for building)

---

## Quick Start

### 1. Prepare Video Files

Create video directories for each peer:

```powershell
mkdir videos_peer1, videos_peer2, videos_peer3
mkdir buffer_peer1, buffer_peer2, buffer_peer3
```

Put different video files in each directory:

```
videos_peer1/
  ├── movie1.mp4
  └── documentary.mp4

videos_peer2/
  ├── movie2.mp4
  └── series_s01e01.mp4

videos_peer3/
  ├── movie3.mp4
  └── tutorial.mp4
```

### 2. Build and Start Docker Containers

```powershell
# Build Docker image
docker compose build

# Start all peers
docker compose up -d

# Verify containers are running
docker compose ps
```

**Note:** Modern Docker Desktop uses `docker compose` (space) instead of `docker-compose` (hyphen).

You should see:
```
NAME         IMAGE                    STATUS
p2p-peer1    network_proje-peer1      Up
p2p-peer2    network_proje-peer2      Up
p2p-peer3    network_proje-peer3      Up
```

### 3. Check Peer Logs

```powershell
# View logs from all peers
docker compose logs -f

# View logs from specific peer
docker logs p2p-peer1 -f
```

Expected output:
```
╔════════════════════════════════════════════════════════════════╗
║          P2P VIDEO STREAMING - HEADLESS PEER MODE              ║
╚════════════════════════════════════════════════════════════════╝

Video directory: /videos
Buffer directory: /buffer

Starting File Server...
✓ File Server started on port: 50001
Starting Discovery Service...
✓ Discovery Service started
✓ Peer ID: peer1

Headless peer is running. Shared files:
  - movie1.mp4 (15234 KB) [a1b2c3d4...]
  - documentary.mp4 (45678 KB) [e5f6g7h8...]

Press Ctrl+C to stop the peer.
```

### 4. Run GUI Peer on Windows Host

**Option A: Connect to Docker Subnet**

Set environment variable to broadcast to Docker network:

```powershell
$env:BROADCAST_ADDRESS="172.20.255.255"
mvn clean compile exec:java
```

**Option B: Use Default Localhost**

Run normally (won't see Docker peers):

```powershell
mvn clean compile exec:java
```

---

## Environment Variables

### Docker Containers

Each peer container supports these environment variables:

| Variable | Description | Default | Example |
|----------|-------------|---------|---------|
| `PEER_ID` | Unique peer identifier | Random UUID | `peer1`, `peer2` |
| `BROADCAST_ADDRESS` | Discovery broadcast IP | `255.255.255.255` | `172.20.255.255` |
| `FILE_SERVER_PORT` | TCP port for file transfer | `50001` | `50001` |
| `VIDEO_DIR` | Directory with shared videos | `/videos` | `/videos` |
| `BUFFER_DIR` | Directory for downloads | `/buffer` | `/buffer` |

### Windows GUI Host

To connect GUI to Docker peers:

```powershell
# PowerShell
$env:BROADCAST_ADDRESS="172.20.255.255"
mvn exec:java

# Or permanently in Windows:
setx BROADCAST_ADDRESS "172.20.255.255"
```

---

## Docker Commands

### Build

```powershell
# Build Docker image
docker compose build

# Build without cache (force rebuild)
docker compose build --no-cache
```

### Run

```powershell
# Start all peers in background
docker compose up -d

# Start with logs visible
docker compose up

# Start specific peer only
docker compose up peer1

# Scale to more peers (modify docker-compose.yml first)
docker compose up --scale peer1=5
```

### Stop

```powershell
# Stop all peers
docker compose down

# Stop and remove volumes
docker compose down -v

# Stop specific peer
docker stop p2p-peer1
```

### Logs

```powershell
# Follow logs from all peers
docker compose logs -f

# Follow logs from specific peer
docker logs p2p-peer1 -f

# Last 100 lines
docker logs p2p-peer1 --tail 100
```

### Inspect

```powershell
# List running containers
docker compose ps

# Inspect container
docker inspect p2p-peer1

# Execute command in container
docker exec -it p2p-peer1 sh

# Check network
docker network inspect network_proje_p2p_network
```

---

## Network Configuration

### Docker Bridge Network

**Subnet:** `172.20.0.0/16`  
**Gateway:** `172.20.0.1`  
**Broadcast:** `172.20.255.255`

### Static IP Assignments

| Peer | Container IP | Host UDP Port | Host TCP Port |
|------|-------------|---------------|---------------|
| peer1 | 172.20.0.2 | 51000 | 51001 |
| peer2 | 172.20.0.3 | 52000 | 52001 |
| peer3 | 172.20.0.4 | 53000 | 53001 |

### Port Mapping

Each container exposes:
- **UDP 50000** (Discovery) → Mapped to host `5X000`
- **TCP 50001** (File Server) → Mapped to host `5X001`

---

## Connecting from Windows GUI

### Discovery Mechanism

Docker containers broadcast to **172.20.255.255** (subnet broadcast).

**Problem:** Windows host uses **255.255.255.255** (global broadcast).

**Solution:** Two options:

#### Option 1: Configure GUI to Use Docker Subnet (Recommended)

```powershell
# Set environment variable before running GUI
$env:BROADCAST_ADDRESS="172.20.255.255"
mvn exec:java
```

Now GUI will discover Docker peers automatically.

#### Option 2: Manual Peer Addition

Future enhancement: Add "Manual Add Peer" button in GUI:
- IP: `172.20.0.2`
- Port: `50001`

---

## Testing

### Test Peer Discovery

```powershell
# Start 2 Docker peers
docker-compose up peer1 peer2 -d

# Check if they discover each other
docker logs p2p-peer1 | grep "Peer found"
docker logs p2p-peer2 | grep "Peer found"
```

Expected output:
```
✓ New Peer Discovered: peer2@172.20.0.3
```

### Test File Sharing

1. Put `test.mp4` in `videos_peer1/`
2. Restart peer1: `docker-compose restart peer1`
3. Check logs: `docker logs p2p-peer1`
4. Should see: "Indexed file: test.mp4"

### Test Download from GUI

1. Start Docker peers: `docker-compose up -d`
2. Set environment: `$env:BROADCAST_ADDRESS="172.20.255.255"`
3. Run GUI: `mvn exec:java`
4. Click "Search Network"
5. Should see files from Docker peers
6. Double-click to download

---

## Troubleshooting

### Issue: Docker containers don't see each other

**Symptoms:**
- Logs show "No peers found"
- Each peer runs but isolated

**Solution:**
```powershell
# Check network
docker network inspect network_proje_p2p_network

# Verify broadcast address
docker exec p2p-peer1 printenv BROADCAST_ADDRESS
# Should show: 172.20.255.255

# Check if UDP port is listening
docker exec p2p-peer1 netstat -an | grep 50000
```

### Issue: GUI can't see Docker peers

**Symptoms:**
- GUI search returns no results
- Docker peers visible in logs but not in GUI

**Solution:**
```powershell
# Ensure BROADCAST_ADDRESS is set for GUI
$env:BROADCAST_ADDRESS="172.20.255.255"

# Verify it's set
echo $env:BROADCAST_ADDRESS

# Run GUI
mvn exec:java
```

### Issue: Docker build fails

**Symptoms:**
- `docker-compose build` fails
- Maven dependency errors

**Solution:**
```powershell
# Clean Maven cache
mvn clean

# Build on host first (to download dependencies)
mvn package

# Then build Docker image
docker-compose build --no-cache
```

### Issue: Port conflicts

**Symptoms:**
- `Error: port is already allocated`

**Solution:**
```powershell
# Find process using port
netstat -ano | findstr :51000

# Kill process
taskkill /PID <PID> /F

# Or change port in docker-compose.yml
```

### Issue: Videos not found

**Symptoms:**
- Peer logs: "(No video files found)"

**Solution:**
```powershell
# Check volume mount
docker inspect p2p-peer1 | grep -A 5 Mounts

# Verify files exist
ls videos_peer1/

# Ensure .mp4 or .mkv extension
# Restart peer
docker-compose restart peer1
```

---

## File Structure

```
Network_Proje/
├── Dockerfile                  # Multi-stage Docker build
├── docker-compose.yml          # Docker Compose configuration
├── DOCKER.md                   # This file
├── pom.xml                     # Maven configuration (updated for JAR)
├── src/
│   └── main/
│       └── java/
│           └── com/network/p2p/
│               ├── P2PVideoApp.java         # Headless mode support
│               ├── network/
│               │   ├── DiscoveryService.java # Broadcast address env var
│               │   └── FileServer.java       # Port env var
│               └── ...
├── videos_peer1/               # Video files for peer1
├── videos_peer2/               # Video files for peer2
├── videos_peer3/               # Video files for peer3
├── buffer_peer1/               # Download buffer for peer1
├── buffer_peer2/               # Download buffer for peer2
└── buffer_peer3/               # Download buffer for peer3
```

---

## Advanced: Adding More Peers

Edit `docker-compose.yml`:

```yaml
  peer4:
    build: .
    container_name: p2p-peer4
    hostname: peer4
    networks:
      p2p_network:
        ipv4_address: 172.20.0.5
    environment:
      - PEER_ID=peer4
      - BROADCAST_ADDRESS=172.20.255.255
      - FILE_SERVER_PORT=50001
      - VIDEO_DIR=/videos
      - BUFFER_DIR=/buffer
    volumes:
      - ./videos_peer4:/videos:ro
      - ./buffer_peer4:/buffer
    ports:
      - "54000:50000/udp"
      - "54001:50001/tcp"
    restart: unless-stopped
```

Then:
```powershell
mkdir videos_peer4, buffer_peer4
docker compose up -d
```

---

## Performance Tips

1. **Limit CPU/Memory** (optional):
   ```yaml
   deploy:
     resources:
       limits:
         cpus: '0.5'
         memory: 512M
   ```

2. **Use volume caching** (Windows):
   ```yaml
   volumes:
     - ./videos_peer1:/videos:ro,cached
   ```

3. **Reduce log verbosity** (edit Java code):
   - Remove debug `System.out.println()` statements
   - Use log levels in production

---

## Cleanup

Remove everything:

```powershell
# Stop and remove containers, networks, volumes
docker compose down -v

# Remove built images
docker rmi network_proje-peer1 network_proje-peer2 network_proje-peer3

# Remove video/buffer directories
rm -r videos_peer*, buffer_peer*
```

---

## Bonus Points Justification

This Docker integration demonstrates:

✅ **Containerization**: Multi-stage Dockerfile for optimal image size  
✅ **Docker Compose**: Multi-peer orchestration with custom network  
✅ **Environment Configuration**: Flexible peer configuration via env vars  
✅ **Network Architecture**: Subnet broadcast for P2P discovery in Docker  
✅ **Volume Management**: Persistent video storage and download buffers  
✅ **Headless Mode**: GUI-free backend peer implementation  
✅ **Scalability**: Easy to add/remove peers via docker-compose  

**Key Achievement:** Successfully runs P2P network in Docker while maintaining GUI on host.

---

## Support

For issues:
1. Check logs: `docker compose logs -f`
2. Verify network: `docker network inspect network_proje_p2p_network`
3. Test connectivity: `docker exec p2p-peer1 ping 172.20.0.2`
4. Review this guide's troubleshooting section

**Note:** All examples use `docker compose` (space) for modern Docker Desktop. If you have legacy docker-compose installed, use `docker-compose` (hyphen) instead.

---

**Last Updated:** January 3, 2026  
**Docker Version:** 24.x+  
**Docker Compose Version:** 2.x+
