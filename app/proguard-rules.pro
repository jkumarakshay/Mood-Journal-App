# Credential Manager discovers this provider from manifest metadata by class name.
# Retain its Context constructor without retaining unrelated SDK compatibility code.
-keep class androidx.credentials.playservices.CredentialProviderPlayServicesImpl {
    public <init>(android.content.Context);
}

# Keep runtime annotations and nesting metadata required by AndroidX/Kotlin libraries.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod,Signature
