#!/system/bin/sh
#
# Sourced by Magisk during installation.

ui_print "- Installing LibrePods as a privileged system app"
ui_print "- Granting BLUETOOTH_PRIVILEGED, MODIFY_PHONE_STATE,"
ui_print "  INTERACT_ACROSS_USERS and LOCAL_MAC_ADDRESS"

# A copy installed the ordinary way lives under /data/app and takes precedence
# over the one this module places in /system/priv-app. When the two are signed
# with different keys the system copy is ignored entirely, so none of the
# privileged permissions above are granted - which looks exactly like the module
# having had no effect. LOCAL_MAC_ADDRESS is the one people notice, because
# without it the app cannot read this phone's Bluetooth address and handoff,
# takeover and "reconnect when last connected here" all silently do nothing.
DATA_COPY=""
for candidate in /data/app/*me.kavishdevar.librepods* /data/app/*/*me.kavishdevar.librepods*; do
  if [ -e "$candidate" ]; then
    DATA_COPY="$candidate"
    break
  fi
done

if [ -n "$DATA_COPY" ]; then
  ui_print " "
  ui_print "! LibrePods is also installed as a normal app:"
  ui_print "!   $DATA_COPY"
  ui_print "! That copy overrides the one in this module. If it was signed with"
  ui_print "! a different key than this zip, the privileged permissions will"
  ui_print "! not be granted and features like handoff will stay broken."
  ui_print "! If so, uninstall it and reboot."
  ui_print " "
fi
