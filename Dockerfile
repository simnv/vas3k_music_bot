FROM maven:3.9-eclipse-temurin-25-alpine AS build

WORKDIR /app

COPY ./pom.xml .

# verify --fail-never works much better than dependency:resolve or dependency:go-offline
RUN mvn clean verify --fail-never

COPY ./src ./src

RUN mvn package -DskipTests

FROM eclipse-temurin:25-alpine
RUN apk update && apk add curl python3 bash ffmpeg mkvtoolnix mutagen deno \
    && curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp \
    && chmod a+rx /usr/local/bin/yt-dlp \
    && yt-dlp --update-to master \
    && rm  -rf /tmp/* /var/cache/apk/*

COPY --from=build /app/target/vas3k_music.jar /usr/local/lib/vas3k_music.jar
# COPY --from=jauderho/yt-dlp:latest /usr/local/bin/yt-dlp /usr/local/bin

ENTRYPOINT ["java","-Xmx32m","-jar","/usr/local/lib/vas3k_music.jar"]
