FROM ubuntu:groovy
LABEL MAINTAINER Cecil Moore <cmoore@sumologic.com>

RUN  apt-get update && apt-get install java-common -y && apt-get install -y curl && apt-get clean && rm -rf /var/lib/apt/lists
RUN  curl -LO https://corretto.aws/downloads/latest/amazon-corretto-11-x64-linux-jdk.deb && \
     dpkg -i amazon-corretto-*.deb && \
     rm -f amazon-corretto-*.deb
RUN  groupadd -r -g 1000 sumo && \
     useradd -r -u 1000 -g 1000 sumo
USER sumo
