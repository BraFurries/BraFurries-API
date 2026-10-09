FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw

COPY src/ src/
RUN ./mvnw -DskipTests package
RUN cp target/API-0.0.1-SNAPSHOT.jar app.jar

FROM eclipse-temurin:21-jre-jammy AS runtime

ENV APP_HOME=/app
WORKDIR ${APP_HOME}

RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir ${APP_HOME} --shell /usr/sbin/nologin app \
    && mkdir -p ${APP_HOME}/tmp \
    && chown app:app ${APP_HOME}/tmp \
    && chmod 700 ${APP_HOME}/tmp

COPY --from=build /workspace/app.jar ${APP_HOME}/app.jar

USER app:app

ENTRYPOINT ["java", "-Djava.io.tmpdir=/app/tmp", "-jar", "/app/app.jar"]
