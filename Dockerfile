FROM alpine:3.14

ADD target/bundle /xapipe
ADD .java_modules /xapipe/.java_modules

# replace the linux runtime via jlink
RUN apk update \
        && apk upgrade \
        && apk add ca-certificates \
        && update-ca-certificates \
        && apk add --no-cache openjdk11 \
        && mkdir -p /xapipe/runtimes \
        && jlink --output /xapipe/runtimes/linux/ --add-modules $(cat /xapipe/.java_modules) \
        && apk del openjdk11 \
        && rm -rf /var/cache/apk/*

WORKDIR /xapipe
ENTRYPOINT ["/xapipe/bin/run.sh"]
CMD ["--help"]
