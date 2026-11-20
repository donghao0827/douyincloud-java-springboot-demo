# 两个阶段共用抖音云 JDK 8 镜像和火山引擎 Debian 软件源。
FROM public-cn-beijing.cr.volces.com/public/base:maven-3.6.0-jdk-8-slim AS base
ARG DEBIAN_MIRROR=http://mirrors.volces.com
RUN set -eu; . /etc/os-release; \
    suite="${VERSION_CODENAME:-}"; \
    if [ -z "$suite" ]; then suite="$(sed -n 's/.*(\([^)]*\)).*/\1/p' /etc/os-release | head -1)"; fi; \
    case "$suite" in \
      jessie|stretch|buster) root="$DEBIAN_MIRROR/debian-archive"; security="$suite/updates" ;; \
      bullseye|bookworm|trixie) root="$DEBIAN_MIRROR"; security="$suite-security" ;; \
      *) echo "Unsupported Debian suite: $suite" >&2; exit 1 ;; \
    esac; \
    printf 'deb %s/debian %s main\ndeb %s/debian-security %s main\n' \
      "$root" "$suite" "$root" "$security" > /etc/apt/sources.list; \
    rm -f /etc/apt/sources.list.d/debian.sources

# 构建阶段：在镜像内编译，不依赖仓库里预先存在的 target/
FROM base AS build

WORKDIR /opt/application
COPY pom.xml settings.xml run.sh ./
COPY src ./src

# 拉取公网依赖较慢，容易失败；settings.xml 里已配镜像源
RUN mvn -s /opt/application/settings.xml -f /opt/application/pom.xml -DskipTests clean package

# 运行阶段：只带 jar 和启动脚本
FROM base

WORKDIR /opt/application
COPY --from=build /opt/application/target/douyincloud-demo-0.0.1-SNAPSHOT.jar ./target/
COPY --from=build /opt/application/run.sh ./
RUN chmod +x /opt/application/run.sh

ENV TZ=Asia/Shanghai
EXPOSE 8000

CMD ["sh","/opt/application/run.sh"]
