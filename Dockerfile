# Maven 构建由云效 JavaBuild 步骤完成，此处只做运行时打包。
# 基础镜像托管在自有 ACR，避免 CI 环境访问 Docker Hub 超时。
# 一次性准备：docker pull eclipse-temurin:17-jre && \
#              docker tag eclipse-temurin:17-jre registry.cn-shanghai.aliyuncs.com/honyar-iot/eclipse-temurin:17-jre && \
#              docker push registry.cn-shanghai.aliyuncs.com/honyar-iot/eclipse-temurin:17-jre
FROM registry.cn-shanghai.aliyuncs.com/honyar-iot/eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd -r app && useradd -r -g app app
COPY target/ai-gateway-*.jar /app/app.jar
COPY scripts/entrypoint.sh /app/entrypoint.sh
RUN chown app:app /app/entrypoint.sh \
    && chmod +x /app/entrypoint.sh
USER app
EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
