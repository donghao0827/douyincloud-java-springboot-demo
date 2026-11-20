# 构建阶段：在镜像内编译，不依赖仓库里预先存在的 target/
FROM public-cn-beijing.cr.volces.com/public/base:maven-3.6.0-jdk-8-slim AS build

WORKDIR /opt/application
COPY pom.xml settings.xml run.sh ./
COPY src ./src

# 拉取公网依赖较慢，容易失败；settings.xml 里已配镜像源
RUN mvn -s /opt/application/settings.xml -f /opt/application/pom.xml -DskipTests clean package

# 运行阶段：只带 jar 和启动脚本
FROM public-cn-beijing.cr.volces.com/public/base:maven-3.6.0-jdk-8-slim

WORKDIR /opt/application
COPY --from=build /opt/application/target/douyincloud-demo-0.0.1-SNAPSHOT.jar ./target/
COPY --from=build /opt/application/run.sh ./
RUN chmod +x /opt/application/run.sh

ENV TZ=Asia/Shanghai
EXPOSE 8000

CMD ["sh","/opt/application/run.sh"]
