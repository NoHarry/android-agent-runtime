# The official SDK's shared HTTP artifact contains a JDK-only fallback. This adapter always
# injects AndroidA2AHttpClient, so these fallback references are unreachable and removed by R8.
-dontwarn java.net.http.HttpClient$Builder
-dontwarn java.net.http.HttpClient$Redirect
-dontwarn java.net.http.HttpClient$Version
-dontwarn java.net.http.HttpClient
-dontwarn java.net.http.HttpHeaders
-dontwarn java.net.http.HttpRequest$BodyPublisher
-dontwarn java.net.http.HttpRequest$BodyPublishers
-dontwarn java.net.http.HttpRequest$Builder
-dontwarn java.net.http.HttpRequest
-dontwarn java.net.http.HttpResponse$BodyHandler
-dontwarn java.net.http.HttpResponse$BodyHandlers
-dontwarn java.net.http.HttpResponse

# ClientBuilder.wrap() uses ServiceLoader.stream(), which is absent below Android 14. The
# adapter bypasses that builder with AndroidClientFactory; checkdiscard makes the suppression
# conditional on R8 proving that the incompatible path is not present in the final APK.
-dontwarn java.util.ServiceLoader$Provider
-checkdiscard class org.a2aproject.sdk.client.ClientBuilder
-checkdiscard class org.a2aproject.sdk.client.http.JdkA2AHttpClient
-checkdiscard class org.a2aproject.sdk.client.http.JdkA2AHttpClient$*

# a2a-java-sdk-spec-grpc retains MapStruct annotations in mapper bytecode, but the JSON-RPC
# Android adapter never reflects on them. Keep the suppression exact so executable gaps fail.
-dontwarn org.mapstruct.BeanMapping
-dontwarn org.mapstruct.Builder
-dontwarn org.mapstruct.CollectionMappingStrategy
-dontwarn org.mapstruct.Mapper
-dontwarn org.mapstruct.MapperConfig
-dontwarn org.mapstruct.Mapping
-dontwarn org.mapstruct.Mappings
-dontwarn org.mapstruct.NullValuePropertyMappingStrategy
-dontwarn org.mapstruct.ReportingPolicy
