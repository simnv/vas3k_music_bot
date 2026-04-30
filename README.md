# vas3k_music_bot

A Telegram bot that processes music links and downloads videos/audio from various platforms. The bot uses [Odesli API](https://odesli.co/) to parse music links and provides unified access to tracks across multiple streaming platforms.

Based on the original project by [AngryJKirk](https://github.com/AngryJKirk/vas3k_music_bot)

## Features

- **Multi-platform music link parsing** via Odesli API (Spotify, YouTube, Apple Music, etc.)
- **Video/Audio downloading** from YouTube, TikTok, VK, RuTube
- **Smart content detection** - automatically sends audio for music-focused chats
- **Scene/freeze analysis** - decides whether to send video by counting scene changes, falling back to freeze detection
- **Quality selector** - choose download quality via keyword in the message (`low`/`medium`/`high`); defaults to medium (≤720p)
- **Video chunking** - automatically splits large videos into smaller chunks for Telegram
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

# Optional: Video chunk size in MB (default: 49)
CHUNK_SIZE_MB=49
```

### 4. Telegram Bot Creation Steps

To create a Telegram bot, follow these steps:

1. **Create a new bot via BotFather**:
   - Open Telegram and search for `@BotFather`
   - Send the command `/newbot`
   - Follow the prompts to set a name and username for your bot
   - Save the API token provided by BotFather

2. **Disable Group Privacy** (important for the bot to work in groups):
   - In the chat with `@BotFather`, send the command `/mybots`
   - Select your bot from the list
   - Choose "Bot Settings" → "Group Privacy"
   - Select "Turn off" to disable group privacy mode
   - This allows the bot to receive all messages in groups where it's a member

3. **Get your Telegram User ID** (for error notifications):
   - Search for `@userinfobot` in Telegram
   - Send it any message to get your user ID

4. **Get Chat IDs** (for allow list):
   - Add your bot to the desired group/channel
   - Send a message to the bot in that chat
   - Check the bot logs or use a service like `@JsonDumpBot` to get the chat ID

### 5. Telegram Bot API Configuration

This project uses a local Telegram Bot API server (aiogram/telegram-bot-api) for better performance and control. To configure it:

1. **Get Telegram API credentials**:
   - Go to [my.telegram.org](https://my.telegram.org/)
   - Sign in with your phone number
   - Go to "API development tools"
   - Create a new application to get your `API_ID` and `API_HASH`

2. **Configure environment variables**:
   Add these to your `.env` file:

   ```env
   # Telegram API credentials (from my.telegram.org)
   TELEGRAM_API_ID=your_api_id
   TELEGRAM_API_HASH=your_api_hash
   
   # Local Telegram Bot API settings
   TELEGRAM_LOCAL=1
   TELEGRAM_BASE_SCHEMA=http
   TELEGRAM_BASE_URL=tgbot-api
   TELEGRAM_BASE_PORT=8081

   # You can set bigger file size with local Telegram Bot API
   CHUNK_SIZE_MB=1800
   ```

3. **Understanding the Telegram Bot API configuration**:
   - `TELEGRAM_API_ID` and `TELEGRAM_API_HASH`: Required for the local Bot API server to connect to Telegram's servers
   - `TELEGRAM_LOCAL`: Set to `1` to use the local Bot API server instead of Telegram's servers
   - `TELEGRAM_BASE_SCHEMA`: Protocol for connecting to the local Bot API server (http/https)
   - `TELEGRAM_BASE_URL`: Hostname of the local Bot API server (matches the service name in docker-compose.yml)
   - `TELEGRAM_BASE_PORT`: Port for connecting to the local Bot API server

### 6. Add Cookies (Optional)

For better download success rates, add a `cookies.txt` file in the project root with your browser cookies for video platforms.

#### Getting Cookies from Firefox

You can extract cookies from Firefox using the `cookies-from-browser` feature of yt-dlp:

1. **Install required dependencies**:
   ```bash
   # On macOS with Homebrew
   brew install yt-dlp
   ```

2. **Extract cookies from Firefox**:
   ```bash
   yt-dlp --cookies-from-browser firefox --cookies cookies.txt --print-traffic https://www.youtube.com
   ```
   
   This will create a `cookies.txt` file in your current directory with cookies from Firefox.

3. **Alternative method using a script**:
   If the above method doesn't work, you can use this Python script with uv:
   
   ```python
   # /// script
   # dependencies = ["browser-cookie3"]
   # ///
   
   import browser_cookie3
   import json
   
   # Get cookies from Firefox
   cj = browser_cookie3.firefox()
   
   # Filter for relevant domains (YouTube, TikTok, etc.)
   domains = ['youtube.com', 'tiktok.com', 'vk.com', 'rutube.ru']
   filtered_cookies = [cookie for cookie in cj if any(domain in cookie.domain for domain in domains)]
   
   # Convert to Netscape format
   with open('cookies.txt', 'w') as f:
       f.write("# Netscape HTTP Cookie File\n")
       f.write("# This is a generated file! Do not edit.\n\n")
       for cookie in filtered_cookies:
           if cookie.value and cookie.name:
               flag = "TRUE" if cookie.domain.startswith('.') else "FALSE"
               path = cookie.path if cookie.path else "/"
               secure = "TRUE" if cookie.secure else "FALSE"
               expiry = str(cookie.expires) if cookie.expires else "0"
               f.write(f"{cookie.domain}\t{flag}\t{path}\t{secure}\t{expiry}\t{cookie.name}\t{cookie.value}\n")
   
   print("Cookies saved to cookies.txt")
   ```
   
   Save this script as `extract_cookies.py` and run it with uvx:
   ```bash
   # Install uvx if you don't have it
   curl -LsSf https://astral.sh/uv/install.sh | sh
   
   # Run the script with uvx (automatically installs dependencies)
   uv run extract_cookies.py
   ```

4. **Place the cookies file**:
   Move the generated `cookies.txt` file to the project root directory.

5. **Verify cookies are working**:
   ```bash
   docker-compose logs -f music
   ```
   Check the logs for successful download messages without authentication errors.

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

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `TELEGRAM_API_TOKEN` | Yes | - | Bot token from @BotFather |
| `TELEGRAM_BOT_USERNAME` | Yes | - | Bot username (without @) |
| `TELEGRAM_ALLOW_LIST` | Yes | - | Allow list of chat ids and their names |
| `ERROR_NOTIFICATION_TELEGRAM_ID` | No | - | User ID for error notifications |
| `IPV6_URL_CONTAINS` | No | - | Comma-separated domains for IPv6 |
| `YTDL_PROXY` | No | - | yt-dlp SOCKS/HTTP proxy URL, e.g. `socks5://our-proxy:1080`. Used together with `YTDL_PROXY_URL_CONTAINS`. |
| `YTDL_PROXY_URL_CONTAINS` | No | - | Comma-separated URL substrings; matching URLs are downloaded via `YTDL_PROXY`. Both vars must be set to activate. |
| `CHUNK_SIZE_MB` | No | 50 | Video chunk size in MB for splitting large files |
| `YTDL_LOCATION` | No | `/usr/local/bin/yt-dlp` | yt-dlp binary location |
| `TELEGRAM_API_ID` | Yes | - | API ID from my.telegram.org |
| `TELEGRAM_API_HASH` | Yes | - | API hash from my.telegram.org |
| `TELEGRAM_LOCAL` | No | - | Set to 1 to use local Bot API server |
| `TELEGRAM_BASE_SCHEMA` | No | `https` | Protocol for Bot API (http/https) |
| `TELEGRAM_BASE_URL` | No | `api.telegram.org` | Hostname of Bot API server |
| `TELEGRAM_BASE_PORT` | No | `443` | Port for Bot API server |

## Docker Configuration

The bot runs in a multi-container setup:

- **music**: Main bot application
- **tgbot-api**: Local Telegram Bot API server (aiogram/telegram-bot-api)

### aiogram/telegram-bot-api Service

The `tgbot-api` service uses the official [aiogram/telegram-bot-api](https://hub.docker.com/r/aiogram/telegram-bot-api) Docker image, which provides a local Telegram Bot API server. This offers several advantages:

- **Reduced latency**: Direct connection to Telegram servers
- **Better reliability**: No dependency on Telegram's public API servers
- **Increased rate limits**: Higher request limits compared to the public API
- **Custom configuration**: Ability to fine-tune API settings

The service is configured with the following environment variables:
- `TELEGRAM_API_ID`: Your Telegram API ID from my.telegram.org
- `TELEGRAM_API_HASH`: Your Telegram API hash from my.telegram.org
- `TELEGRAM_LOCAL`: Set to true for local API mode
- `TELEGRAM_VERBOSITY`: (Optional) Logging verbosity level (0-5)

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

## Usage

Send a supported link in an authorized chat. Optionally add a quality keyword anywhere in the message:

- `low` / `l` — up to 480p
- `medium` / `med` / `mid` / `m` — up to 720p (default)
- `high` / `hi` / `h` — best available

Single-letter aliases (`l`/`m`/`h`) are only recognized at the start or end of the message.

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

If configured, the bot sends error notifications to the specified Telegram user ID.

## Architecture

- **Kotlin/JVM**: Main application language
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
