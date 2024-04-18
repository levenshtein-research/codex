FROM arangodb:3.9.2

# to be used in healthcheck
RUN apk --update --no-cache add curl
CMD ["arangod", "--transaction.streaming-idle-timeout", "120"]
