-- Dev only: the demo tenant's secret key is "sk_dev_demo_only_not_a_secret_0123456789abcdef",
-- encrypted with the dev encryption key from application-dev.yml. test-host/identify.html signs with it.
update tenants
set secret_key_enc = decode('b72fc010ef5c8148295387f255353534940e6de1e7b017a8ec29c1a9e39fee4bc83343a5f342143c19749801e58e5b18d1c009b1353f979ca72ea4196cfccec29382e6364e7a2f9a9e5b', 'hex')
where slug = 'demo';
