FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY src ./src
RUN mkdir out && javac -d out $(find src/main -name '*.java')

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/out ./out
ENV BIND=0.0.0.0 PORT=6380 AOF_FILE=/data/appendonly.aof
VOLUME /data
EXPOSE 6380 8081
CMD ["java", "-cp", "out", "com.mohit.kv.Main"]
