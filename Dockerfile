FROM alpine:3.14

ADD target/bundle /xapipe

# replace the linux runtime via jlink
RUN apk update \
        && apk upgrade \
        && apk add ca-certificates \
        && update-ca-certificates \
        && apk add --no-cache openjdk11 \
        && rm -rf /xapipe/runtimes/linux \
        && jlink --output /xapipe/runtimes/linux/ --add-modules java.base,java.logging,java.naming,java.xml,java.sql,java.transaction.xa,java.security.sasl,java.management \
        && apk del openjdk11 \
        && rm -rf /var/cache/apk/*

WORKDIR /xapipe
ENTRYPOINT ["/xapipe/bin/run.sh"]
CMD ["--help"]
