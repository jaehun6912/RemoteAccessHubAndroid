# kotlinx.serialization은 자체 R8 규칙을 함께 배포한다. 설정·판독 결과 모델은 이름으로 JSON과 맞추므로 필드 이름을 유지한다.
-keepclassmembers @kotlinx.serialization.Serializable class io.github.jaehun6912.remoteaccesshub.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepattributes *Annotation*, InnerClasses
