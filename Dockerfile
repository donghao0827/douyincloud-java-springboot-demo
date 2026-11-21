# 编译环境与运行时独立，运行时从受维护的 Debian 源安装浏览器。
FROM public-cn-beijing.cr.volces.com/public/base:maven-3.6.0-jdk-8-slim AS build

WORKDIR /opt/application
COPY pom.xml settings.xml run.sh ./
COPY src ./src

# 拉取公网依赖较慢，容易失败；settings.xml 里已配镜像源
RUN mvn -s /opt/application/settings.xml -f /opt/application/pom.xml -DskipTests clean package

# 运行阶段：只带 jar 和启动脚本
FROM debian:bookworm-slim

# 方案 A：Java 服务内用 Chromium CLI 截图，不跑 Node/Puppeteer
RUN sed -i 's|deb.debian.org|mirrors.volces.com|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get update && apt-get install -y --no-install-recommends \
      openjdk-17-jre-headless chromium fonts-noto-cjk \
    && chromium --version && java -version \
    && rm -rf /var/lib/apt/lists/*

ENV CHROME_PATH=/usr/bin/chromium

WORKDIR /opt/application
COPY --from=build /opt/application/target/douyincloud-demo-0.0.1-SNAPSHOT.jar ./target/
COPY --from=build /opt/application/run.sh ./
RUN chmod +x /opt/application/run.sh

ENV TZ=Asia/Shanghai
EXPOSE 8000

CMD ["sh","/opt/application/run.sh"]
