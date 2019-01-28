FROM openjdk:8

# Copy to images tomcat path
ARG JAR_FILE
ADD target/${JAR_FILE} /usr/local/FROST/FROST-Importer.jar
WORKDIR /usr/local/FROST
CMD ["java", "-XX:+UnlockExperimentalVMOptions", "-XX:+UseCGroupMemoryLimitForHeap", "-jar", "FROST-Importer.jar"]