FROM eclipse-temurin:17-jdk
ENV SPRING_PROFILES_ACTIVE=dev
ADD target/backend.jar backend.jar
EXPOSE 8081
ENTRYPOINT ["java","-jar","backend.jar"]