ARG OCI_LENS_JAVA_RUNTIME=eclipse-temurin:21-jre-jammy
ARG OCI_LENS_MAVEN_BUILDER=maven:3.9.11-eclipse-temurin-21
FROM ${OCI_LENS_MAVEN_BUILDER} AS builder

WORKDIR /workspace

COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

FROM ${OCI_LENS_JAVA_RUNTIME}

ARG APP_UID=10001
ARG APP_GID=10001

RUN groupadd --gid ${APP_GID} monitor \
  && useradd --uid ${APP_UID} --gid ${APP_GID} --create-home --shell /usr/sbin/nologin monitor \
  && mkdir -p /app /data /home/monitor/.oci \
  && chown -R monitor:monitor /app /data /home/monitor

WORKDIR /app

COPY --from=builder /workspace/target/oci-lens-server-*.jar /app/app.jar

ENV OCI_MONITOR_DB=/data/oci-lens.db
ENV OCI_CONFIG_FILE_PATH=/home/monitor/.oci/config
ENV OCI_CONFIG_PROFILE=DEFAULT

EXPOSE 9090

LABEL org.oci-lens.admin-password-reset="1"

USER monitor

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
