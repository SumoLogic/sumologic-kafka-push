FROM ubuntu:focal
LABEL MAINTAINER Cecil Moore <cmoore@sumologic.com>

RUN apt-get update && apt-get install -y wget && apt-get install -y gnupg && apt-get install -y software-properties-common
RUN wget -O - https://apt.corretto.aws/corretto.key | apt-key add - 
RUN add-apt-repository 'deb https://apt.corretto.aws stable main'
RUN apt-get install java-common -y && apt-get install -y curl && apt-get clean && rm -rf /var/lib/apt/lists
RUN apt-get update && apt-get install -y java-11-amazon-corretto-jdk
RUN groupadd -r -g 1000 sumo && \
     useradd -r -u 1000 -g 1000 sumo
USER sumo
