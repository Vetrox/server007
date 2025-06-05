# setup

generate self-signed certificate and trust it in the client

## server

run
`$ keytool -genkeypair -alias server007 -keyalg RSA -keysize 2048 -keystore keystore007.jks -storepass changeit -validity 36500 -dname "CN=localhost, OU=dev, O=vetrox, L=james, ST=bond, C=XX" -ext "SAN=dns:localhost"`
and specify password `changeit`.

### client

```
-javaagent:/path/to/proxy007-1.0.0.jar-javaagent:/path/to/proxy007-1.0.0.jar=trust=/path/to/keystore007.jks
```

