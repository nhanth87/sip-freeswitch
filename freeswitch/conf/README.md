# Elisa FreeSWITCH overlays

The compose file retains the `safarov/freeswitch` image's default configuration and bind-mounts only
these overlays: ESL on `8021`, XML curl to `http://elisa:8088/fs/xml`, and the external Sofia profile
on SIP `5060`. This avoids replacing the image's complete default dialplan and module configuration.
