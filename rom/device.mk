# 加进 device.mk / product.mk

# 1. Agent 本体 + 权限白名单
PRODUCT_PACKAGES += \
    Pilot \
    privapp-permissions-com.pilot.agent

# 2. 设为默认助理（长按 Home / 电源键直接唤起 Pilot）
PRODUCT_PACKAGES += \
    PilotAssistantOverlay

# 3. 开机默认启用无障碍（仅政企定制版建议开启）
# PRODUCT_PACKAGES += pilot_settings_defaults

# 4. 如果是 Android 17+ 且要用 Computer Control，确认平台侧特性开关
# PRODUCT_PROPERTY_OVERRIDES += \
#     persist.sys.computer_control.enabled=1

# 5. 防止系统在低内存时把 Pilot 杀掉
PRODUCT_PROPERTY_OVERRIDES += \
    ro.pilot.agent.version=0.1.0

# 6. CTS：预置特权应用会被检查权限白名单完整性，
#    如果出现 PrivilegedPermissionAllowlist 相关失败，
#    说明 privapp-permissions 里有权限名写错或漏装文件。
