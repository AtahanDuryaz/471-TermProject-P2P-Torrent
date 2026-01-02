# P2P Video Streaming Application

Peer-to-peer video paylaşım ve progressive streaming uygulaması. VLC kullanarak videoları indirirken izleme imkanı sunar.

## 📋 Özellikler

- **P2P Dosya Paylaşımı**: Peer-to-peer mimaride video dosyalarını paylaşma
- **Progressive Streaming**: İndirme tamamlanmadan video oynatma
- **Round-Robin Chunk Distribution**: BitTorrent tarzı chunk dağıtımı
- **UDP Peer Discovery**: Otomatik peer keşfi ve bağlantı kurma
- **VLC Entegrasyonu**: VLCj ile embedded video player
- **Çoklu Peer Desteği**: Aynı anda birden fazla peer'dan chunk indirme

## 🛠️ Teknolojiler

- **Java 17**
- **Maven** - Dependency management
- **VLCj 4.8.3** - VLC media player entegrasyonu
- **Swing** - GUI framework
- **UDP Multicast** - Peer discovery

## 📦 Proje Yapısı

```
Network_Proje/
├── src/main/java/com/network/p2p/
│   ├── P2PVideoApp.java              # Ana uygulama entry point
│   ├── gui/
│   │   ├── MainFrame.java            # Ana GUI penceresi (VLC player, download UI)
│   │   └── VideoSearchResult.java    # Video search sonucu data class
│   ├── managers/
│   │   ├── DownloadManager.java      # Download koordinasyonu ve chunk yönetimi
│   │   ├── DownloadWorker.java       # Her peer için download thread
│   │   ├── FileManager.java          # Dosya paylaşımı ve yönetimi
│   │   └── PeerManager.java          # Peer listesi ve durum takibi
│   └── network/
│       ├── DiscoveryService.java     # UDP peer discovery servisi
│       ├── FileServer.java           # TCP chunk server
│       └── Protocol.java             # Network protokol mesajları
├── pom.xml                            # Maven dependencies
└── README.md                          # Bu dosya

# KULLANILMAYAN TEST DOSYALARI (Silinebilir):
├── ChunkCopyWithHash.java            # Test: Chunk kopyalama ve hash doğrulama
├── ChunkFileClient.java              # Test: Basit chunk client
├── ChunkFileServer.java              # Test: Basit chunk server
├── DiscoveryReceiver.java            # Test: UDP discovery alıcı
└── DiscoverySender.java              # Test: UDP discovery gönderici
```

## 🚀 Kurulum ve Çalıştırma

### Gereksinimler

- Java 17 veya üzeri
- Maven 3.x
- VLC Media Player (sistem PATH'inde olmalı)

### Derleme ve Çalıştırma

```bash
# Derleme ve çalıştırma (tek komut)
mvn clean compile exec:java

# Sadece derleme
mvn clean compile

# Sadece çalıştırma (derlenmiş projede)
mvn exec:java
```

### İlk Kullanım

1. Uygulamayı başlat: `mvn clean compile exec:java`
2. Buffer klasörü seç (Share Folder butonu)
3. Paylaşmak istediğin videoları bu klasöre koy
4. "Search Files" ile ağdaki peer'ları tara
5. Listeden video seç ve "Download & Play" butonuna bas

## 🎯 Nasıl Çalışır?

### 1. Peer Discovery
- UDP multicast (255.255.255.255:50000) ile peer bulma
- Her 5 saniyede bir HELLO paketi yayını
- TTL-based packet forwarding (maksimum 3 hop)
- Otomatik peer listesi güncelleme

### 2. File Sharing
- Her peer bir TCP FileServer (rastgele port) çalıştırır
- Dosyalar 256KB chunk'lara bölünür
- SHA-256 hash ile dosya tanımlama
- Paylaşılan dosya listesi peer'lara duyurulur

### 3. Download Süreci
```
1. Peer'dan dosya metadata'sı istenir (LIST)
2. Round-robin chunk dağıtımı yapılır
   - Chunk 0 → Peer 1
   - Chunk 1 → Peer 2
   - Chunk 2 → Peer 3
   - Chunk 3 → Peer 1 (tekrar)
3. Her peer için DownloadWorker thread başlatılır
4. Chunk'lar paralel indirilir
5. Sequential chunk kontrolü yapılır
6. İlk chunk'lar gelince VLC başlatılır
```

### 4. Progressive Streaming
- VLC player, indirme devam ederken videoyu oynatır
- Her chunk geldiğinde sequential durum kontrol edilir
- Eğer oynatma pozisyonu eksik chunk'a ulaşırsa pause olur
- Eksik chunk gelince otomatik resume olur

## 📊 Network Protokolü

### UDP Discovery Messages (Port 50000)
```
Format: TYPE|TTL|CONTENT

HELLO: 1|3|ID:<peer-id>:PORT:<file-server-port>
LIST:  2|3|<file1-name>:<file1-hash>,...
```

### TCP File Transfer (Rastgele Port)
```
Client → Server:
  [1 byte: command]
  - 0x01: LIST (dosya listesi iste)
  - 0x02: GET (chunk iste)
  
  GET için:
  [4 bytes: chunk index]
  [32 bytes: SHA-256 hash]

Server → Client:
  [1 byte: status]
  - 0x01: SUCCESS
  - 0xFF: ERROR
  
  SUCCESS için:
  [4 bytes: chunk size]
  [N bytes: chunk data]
```

## ⚙️ Konfigürasyon

### Download Settings
- **Chunk Size**: 256 KB
- **Max Workers**: Peer sayısına göre dinamik
- **Connection Timeout**: 5 saniye

### VLC Settings
```java
":file-caching=300"      // 300ms file caching
":network-caching=300"   // 300ms network caching
":live-caching=300"      // 300ms live caching
":clock-jitter=0"        // Saat jitter'ı kapat
":clock-synchro=0"       // Saat senkronizasyonu kapat
```

### Discovery Settings
- **Broadcast Interval**: 5 saniye
- **Discovery Port**: 50000 (UDP)
- **Max TTL**: 3 hops

## 🐛 Bilinen Sorunlar ve Çözümler

### Video %92'de Başlıyor
- **Neden**: VLC media yükleme ve buffering süreci
- **Etki**: Video geç başlıyor ama çalışıyor
- **Durum**: Kabul edilebilir, çok stabil çalışıyor

### VLC Bulunamadı Hatası
```bash
# Windows: VLC'yi PATH'e ekle
setx PATH "%PATH%;C:\Program Files\VideoLAN\VLC"

# Linux/Mac: libvlc yükle
sudo apt-get install vlc libvlc-dev  # Ubuntu/Debian
brew install vlc                       # macOS
```

### Port Already in Use
- FileServer rastgele port kullanır, genelde sorun olmaz
- Discovery port (50000) kullanımdaysa başka uygulama kapatılmalı

## 📈 Performance İpuçları

1. **Chunk Size**: 256KB optimal, değiştirme
2. **Peer Sayısı**: 2-3 peer ideal, çok peer yavaşlatabilir
3. **Network**: Aynı LAN'da en iyi performans
4. **VLC**: Güncel VLC versiyonu kullan (3.0+)

## 🔍 Debug ve Loglama

Uygulama detaylı debug logları üretir:

```
DEBUG CHUNK LISTENER: Chunk X received           # Chunk geldi
DEBUG GUI: VLC is currently playing: true/false  # VLC durumu
DEBUG TIMER: Download active: true               # Timer durumu
DEBUG Worker[ID]: Processing chunk X             # Worker durumu
╔═══════════════════════════════════╗
║ CHUNK RECEIVED - filename.mp4      ║           # Chunk info box
╠═══════════════════════════════════╣
║ Chunk Index: X / Y                 ║
║ From Peer: peer-id                 ║
║ Last Consecutive: Z                ║
╚═══════════════════════════════════╝
```

## 📝 SİLİNEBİLECEK DOSYALAR

Aşağıdaki dosyalar sadece test amaçlı, ana uygulama tarafından **KULLANILMIYOR**:

```
❌ ChunkCopyWithHash.java       # Test dosyası - Chunk kopyalama testi
❌ ChunkFileClient.java          # Test dosyası - Basit chunk client
❌ ChunkFileServer.java          # Test dosyası - Basit chunk server  
❌ DiscoveryReceiver.java        # Test dosyası - UDP discovery test
❌ DiscoverySender.java          # Test dosyası - UDP discovery test
❌ video.mp4                     # Örnek video (gerekirse sakla)
❌ img.jpg                       # Örnek resim
❌ CSE471-Term_Project (2).pdf   # Döküman dosyası
```

### Silme Komutu

```bash
# Root dizindeki test dosyalarını sil
rm ChunkCopyWithHash.java ChunkFileClient.java ChunkFileServer.java
rm DiscoveryReceiver.java DiscoverySender.java
```

**NOT**: `src/main/java` altındaki dosyalar ana uygulamanın parçası, **SİLME**!

## 🎓 Geliştirme Notları

### Ana Sınıflar ve Sorumlulukları

#### P2PVideoApp.java
- Entry point
- FileManager, PeerManager, DiscoveryService başlatır
- GUI'yi açar

#### MainFrame.java
- Swing GUI
- VLC player container
- Download progress tracking
- Chunk listener ve playback kontrolü

#### DownloadManager.java
- Active download'ları yönetir
- Chunk distribution (round-robin)
- Sequential chunk tracking
- Worker thread koordinasyonu

#### DownloadWorker.java
- Her peer için ayrı thread
- Queue-based chunk requests
- TCP connection yönetimi
- Chunk data transfer

#### FileServer.java
- TCP server (chunk serving)
- LIST ve GET komutları
- Chunk'ları dosyadan okuyup gönderir

#### DiscoveryService.java
- UDP multicast sender/receiver
- HELLO ve LIST paketleri
- TTL-based forwarding
- Peer discovery ve tracking

## 📞 Sorun Giderme

### Peer Bulunamıyor
1. Firewall kontrol et (UDP 50000 açık olmalı)
2. Aynı network'te olduğundan emin ol
3. "Search Files" tekrar tıkla

### Video Oynatılmıyor
1. VLC kurulu mu kontrol et: `vlc --version`
2. Buffer klasöründe dosya oluştu mu kontrol et
3. VLC log'larına bak (konsol çıktısı)

### Download Donuyor
1. Peer hala aktif mi kontrol et
2. Konsol log'larını incele
3. Uygulamayı yeniden başlat

## 📄 Lisans

Bu proje CSE471 Network Programming dersi için geliştirilmiştir.

## 👨‍💻 Geliştirici

Atakan - CSE471 Term Project 2026

---

**Son Güncelleme**: 2 Ocak 2026  
**Versiyon**: 1.0-SNAPSHOT  
**Durum**: ✅ Çalışıyor (Video %92'de başlıyor, progressive streaming aktif)
