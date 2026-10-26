// Run with Groovy 3, from any directory; no Git metadata or Android SDK is needed.
File repository = new File(args[0])
def releaseVersion = new GroovyShell(new Binding([ext: new Expando()]))
        .evaluate(new File(repository, 'gradle/apk-naming.gradle'))
for (int code : [10, 17, 99999]) {
    def version = releaseVersion(code)
    assert version.versionCode == code
    assert version.versionName == "1.${code}.0"
    assert version.apkFileName == "Daily-Pause-v1.${code}.0.apk"
    assert version.tag == "v1.${code}.0"
    assert version.title == "Daily Pause 1.${code}.0"
}
for (Object invalid : [null, '', '0', '-1', '01', '1.5.2', '100000', '2147483648', 'main']) {
    try { releaseVersion(invalid); assert false: "Accepted invalid releaseCode: ${invalid}" }
    catch (IllegalArgumentException expected) { }
}
Properties properties = new Properties()
new File(repository, 'version.properties').withInputStream { properties.load(it) }
def current = releaseVersion(properties.getProperty('releaseCode'))
println "Release version checks passed: ${current}"
