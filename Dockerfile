FROM eclipse-temurin:21-jre
ARG GIT_SHA=dev
ENV GIT_SHA=$GIT_SHA
WORKDIR /app
COPY build/libs/*.jar /app/app.jar
EXPOSE 8091 9091
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
