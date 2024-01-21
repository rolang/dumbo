FROM alpine:latest

RUN apk update && apk add gcompat libgcc libstdc++ s2n-tls utf8proc
RUN wget https://github.com/rolang/dumbo/releases/latest/download/dumbo-cli-x86_64-linux -O /usr/local/bin/dumbo && chmod +x /usr/local/bin/dumbo

ENTRYPOINT ["/usr/local/bin/dumbo"]
CMD ["help"]