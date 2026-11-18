# Third-party notices

- JavaMail for Android 1.6.7, com.sun.mail:android-mail. Copyright Oracle and/or its affiliates and Eclipse contributors. CDDL 1.1 or GPL v2 with Classpath Exception. Upstream: https://github.com/javaee/javamail (Android-specific distribution). Used without source modifications.
- JavaBeans Activation Framework for Android 1.6.7, com.sun.mail:android-activation. Same dual licensing; upstream distribution includes full license text.
- Complete licenses and notices from the two dependency JARs are retained inside smsrelay.jar under third-party/.
- Android framework interfaces are accessed on-device through the system's own generated wrappers. No Android framework binaries are redistributed in the module.
- D8, Android SDK and Temurin JDK are build tools, downloaded separately with hashes in scripts/tools.lock.json; they are not shipped in the module ZIP.
- SMSForwarder commit bc6c89e04c6684a3124e18f05dd61e50df2a6bcb was read for comparison of module layout and pitfalls. No source code was copied from it.
