$(call inherit-product, build/make/target/product/aosp_arm64.mk)
PRODUCT_NAME := sakura_gsi_arm64
PRODUCT_DEVICE := gsi
PRODUCT_BRAND := SakuraOS
PRODUCT_MODEL := SakuraOS GSI ARM64
PRODUCT_MANUFACTURER := SakuraOS
PRODUCT_ENFORCE_ARTIFACT_PATH_REQUIREMENTS := relaxed
PRODUCT_SYSTEM_PROPERTIES += ro.sakura.version=0.2.0 ro.sakura.build_status=untested
PRODUCT_PACKAGES += SakuraHome SakuraSettings SakuraFiles SakuraPhone SakuraMessages \
    SakuraContacts SakuraGallery SakuraCamera SakuraClock SakuraCalculator SakuraCalendar \
    SakuraMusic SakuraRecorder SakuraNotes SakuraInstaller SakuraWallpapers SakuraUpdater \
    SakuraTerminal SakuraCoreManager SakuraPatch sakurad sakura SakuraSystemUIOverlay
# Stock providers, roles, SystemUI/Quickstep and permission controllers remain intact.
# No stock application is disabled until its replacement contracts are validated.
