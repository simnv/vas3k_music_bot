# vas3k_music_bot

A Telegram bot that processes music links and downloads videos/audio from various platforms. The bot uses [Odesli API](https://odesli.co/) to parse music links and provides unified access to tracks across multiple streaming platforms.

## Features

- **Multi-platform music link parsing** via Odesli API (Spotify, YouTube, Apple Music, etc.)
- **Video/Audio downloading** from YouTube, TikTok, VK, RuTube
- **Smart content detection** - automatically sends audio for music-focused chats
- **Freeze detection** - analyzes video content to determine should we send video
- **Allow list system** - restricts bot usage to authorized chats
- **Error notifications** - sends error reports to configured Telegram ID
- **IPv6/IPv4 support** - configurable IP version for downloads

## Prerequisites

- Docker and Docker Compose
- Java 17+ (for local development)
- Maven 3.8+ (for local development)

## Quick Start

### 1. Clone and Setup

```bash
git clone <repository-url>
cd vas3k_music_bot
```

### 2. Configure Environment

Copy the example environment file and fill in your credentials:

```bash
cp .env .env.local
```

Edit `.env` with your configuration:

```env
# Telegram Bot credentials (get from @BotFather)
TELEGRAM_API_TOKEN=your_bot_token
TELEGRAM_BOT_USERNAME=your_bot_username

# Allow list of chat ids and their names
TELEGRAM_ALLOW_LIST=CHAT_ID_1:music,CHAT_ID_2:music_2

# Optional: Error notifications
ERROR_NOTIFICATION_TELEGRAM_ID=your_telegram_user_id

# Optional: IPv6 URL handling
IPV6_URL_CONTAINS=youtube.com,youtu.be
```

### 4. Add Cookies (Optional)

For better download success rates, add a `cookies.txt` file in the project root with your browser cookies for video platforms.

## Deployment

### Development/Testing

```bash
# Deploy for testing
make deploy-test

# Redeploy with rebuild
make redeploy-test
```

### Production

1. Create production environment file:
```bash
cp .env production.env
# Edit production.env with production values
```

2. Deploy to production:
```bash
# Deploy production
make deploy-prod

# Redeploy production with rebuild
make redeploy-prod
```

## Environment Variables Reference

| Variable | Required | Description |
|----------|----------|-------------|
| `TELEGRAM_API_TOKEN` | Yes | Bot token from @BotFather |
| `TELEGRAM_BOT_USERNAME` | Yes | Bot username (without @) |
| `TELEGRAM_ALLOW_LIST` | Yes | Allow list of chat ids and their names |
| `ERROR_NOTIFICATION_TELEGRAM_ID` | No | User ID for error notifications |
| `IPV6_URL_CONTAINS` | No | Comma-separated domains for IPv6 |
| `YTDL_LOCATION` | Auto | yt-dlp binary location (set by Docker) |

## Docker Configuration

The bot runs in a multi-container setup:

- **music**: Main bot application
- **tgbot-api**: Local Telegram Bot API server

### Volumes

- `./cookies.txt`: Browser cookies for downloads

### Networks

- `tg`: Internal network for bot communication

## Local Development

### Build and Run

```bash
# Build the project
mvn clean package

# Run locally (requires environment setup)
java -jar target/vas3k_music.jar
```

### Development Commands

```bash
# Clean build
mvn clean compile

# Run tests
mvn test

# Package without tests
mvn package -DskipTests
```

## Bot Commands

- `/help` - Show help message
- `/test_error` - Test error notification system

## Supported Platforms

### Music Link Parsing (via Odesli)
- Spotify
- YouTube / YouTube Music
- Apple Music / iTunes
- Yandex Music
- SoundCloud
- Google Play Music

### Video/Audio Download
- YouTube (`youtube.com`, `youtu.be`)
- TikTok (`tiktok.com`, `vt.tiktok.com`)
- VK (`vk.com`, `vk.ru`, `vkvideo.ru`)
- RuTube (`rutube.ru`)

## Troubleshooting

### Common Issues

1. **Bot not responding**: Check allow list configuration in `Main.kt`
2. **Download failures**: Ensure `cookies.txt` is present and valid
3. **Permission errors**: Check Docker volume permissions

### Logs

View container logs:
```bash
# All services
docker-compose logs -f

# Bot only
docker-compose logs -f music

# Telegram API
docker-compose logs -f tgbot-api
```

### Error Notifications

If configured, the bot sends error notifications to the specified Telegram user ID. Use `/test_error` command to verify the notification system.

## Architecture

- **Kotlin/JVM**: Main application language
- **Ktor**: HTTP server for Spotify OAuth
- **Telegram Bots API**: Bot framework
- **yt-dlp**: Video/audio downloading
- **FFmpeg**: Media processing and analysis
- **Docker**: Containerization and deployment

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Test thoroughly
5. Submit a pull request
