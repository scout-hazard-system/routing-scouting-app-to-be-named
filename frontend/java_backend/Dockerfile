FROM eclipse-temurin:21-jdk-jammy

RUN apt-get update \
  && apt-get install -y --no-install-recommends python3 python3-requests ca-certificates \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace/frontend/java_backend

EXPOSE 18080

CMD ["bash", "-lc", "./build_executable.sh && java -jar dist/scanner-backend-lite.jar"]
