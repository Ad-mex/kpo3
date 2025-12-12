# download postgres
FROM alpine AS jdbc_downloader
RUN apk add --no-cache curl
RUN curl -L -o /postgres.jar \
    https://jdbc.postgresql.org/download/postgresql-42.7.1.jar


# build
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /build
COPY src ./src
COPY --from=jdbc_downloader /postgres.jar ./lib/postgres.jar

RUN mkdir out \
 && find src -name "*.java" > sources.txt \
 && javac -cp lib/postgres.jar -d out @sources.txt

RUN echo "Main-Class: com.kpo3.storage.StorageServer" > MANIFEST.MF
RUN jar cfm app.jar MANIFEST.MF -C out .


# runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

COPY --from=builder /build/app.jar ./app.jar
COPY --from=builder /build/lib/postgres.jar ./lib/postgres.jar

EXPOSE 8081

CMD ["java", "-cp", "app.jar:lib/postgres.jar", "com.kpo3.storage.StorageServer"]
