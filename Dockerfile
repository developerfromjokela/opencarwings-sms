FROM alpine:3.20

RUN apk add --no-cache \
    openjdk17-jre-headless \
    bash \
    curl \
    jq

WORKDIR /app

RUN LATEST_URL=$(curl -s https://api.github.com/repos/developerfromjokela/opencarwings-sms/releases/latest \
    | jq -r '.assets[] | select(.name | endswith(".jar")) | .browser_download_url') \
    && echo "Downloading from: $LATEST_URL" \
    && curl -L -o /app/app.jar "$LATEST_URL"

COPY entry.sh /app/entry.sh
RUN chmod +x /app/entry.sh

ENTRYPOINT ["/app/entry.sh"]
