FROM maven:3.6.8-jdk-8 AS MAVEN_BUILD 

# copy the pom and src code to the container 
COPY ./ ./  

# package our application code 
RUN mvn clean install -DskipTests -Ddremio.oss-only=true -Dlicense.skip=true -X; \
    ls -al ./distribution/

FROM openjdk:8-jdk as run

MAINTAINER Dremio

# copy only the artifacts we need from the first stage and discard the rest 
COPY --from=MAVEN_BUILD /dremio-oss/distribution/target/demo-0.0.1-SNAPSHOT.jar /demo.jar 

#ARG DOWNLOAD_URL=https://download.dremio.com/community-server/3.0.1-201811132128360291-804fe82/dremio-community-3.0.1-201811132128360291-804fe82.tar.gz
ARG DOWNLOAD_URL

RUN \
  mkdir -p /opt/dremio \
  && mkdir -p /var/lib/dremio \
  && mkdir -p /var/run/dremio \
  && mkdir -p /var/log/dremio \
  && mkdir -p /opt/dremio/data \
  \
  && groupadd --system dremio \
  && useradd --base-dir /var/lib/dremio --system --gid dremio dremio \
  && chown -R dremio:dremio /opt/dremio/data \
  && chown -R dremio:dremio /var/run/dremio \
  && chown -R dremio:dremio /var/log/dremio \
  && chown -R dremio:dremio /var/lib/dremio \
  && wget -q "${DOWNLOAD_URL}" -O dremio.tar.gz \
  && tar vxfz dremio.tar.gz -C /opt/dremio --strip-components=1 \
  && rm -rf dremio.tar.gz

EXPOSE 9047/tcp
EXPOSE 31010/tcp
pen
EXPOSE 45678/tcp

USER dremio
WORKDIR /opt/dremio
ENV DREMIO_HOME /opt/dremio
ENV DREMIO_PID_DIR /var/run/dremio
ENV DREMIO_GC_LOGS_ENABLED="no"
ENV DREMIO_LOG_DIR="/var/log/dremio"
ENV SERVER_GC_OPTS="-XX:+PrintGCDetails -XX:+PrintGCDateStamps"
ENTRYPOINT ["bin/dremio", "start-fg"]
