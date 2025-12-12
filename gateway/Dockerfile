# build
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /build

COPY src ./src

RUN mkdir out \
 && find src -name "*.java" > sources.txt \
 && javac -d out @sources.txt

RUN echo "Main-Class: com.kpo3.gateway.ApiGateway" > MANIFEST.MF
RUN jar cfm app.jar MANIFEST.MF -C out .


# runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

COPY --from=builder /build/app.jar ./app.jar

EXPOSE 8080

CMD ["java", "-jar", "app.jar"]
