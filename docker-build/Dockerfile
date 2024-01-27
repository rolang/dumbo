FROM alpine:latest

RUN apk update && apk add gcompat libgcc libstdc++ s2n-tls utf8proc
ADD --chmod=+x bin/dumbo-cli-x86_64-linux /usr/local/bin/dumbo

ENTRYPOINT ["/usr/local/bin/dumbo"]
CMD ["help"]