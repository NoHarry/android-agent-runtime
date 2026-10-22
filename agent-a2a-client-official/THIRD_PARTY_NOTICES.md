# Third-party notices

This module depends on A2A Java SDK `1.1.0.Final`, licensed under Apache-2.0.

The upstream `a2a-java-sdk-common` artifact and `a2a-java-sdk-spec` artifact both
contain `org.a2aproject.sdk.util.package-info`, which Android rejects as a
duplicate class. The seven small common classes required by the SDK are included
as source from the same pinned release, while the defective common JAR is
excluded. No package-info source is copied. Upstream source:
`https://github.com/a2aproject/a2a-java/tree/v1.1.0.Final`.
