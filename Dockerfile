# Builds and runs the Scalatra banking API.

FROM eclipse-temurin:21-jdk AS build
ARG SBT_VERSION=2.0.9
RUN curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" \
      | tar -xz -C /opt
ENV PATH="/opt/sbt/bin:${PATH}"
WORKDIR /app

# Resolve dependencies in their own layer so source changes rebuild faster.
COPY build.sbt ./
COPY project/build.properties project/plugins.sbt project/
RUN sbt -batch update

COPY src src
RUN sbt -batch stage \
 && cp -r target/out/jvm/scala-*/scalatra-ping-api/universal/stage /stage

FROM eclipse-temurin:21-jre
RUN useradd --system --create-home app
WORKDIR /app
COPY --from=build --chown=app /stage .
USER app
ENV PORT=8080
EXPOSE 8080
CMD ["bin/scalatra-ping-api"]
