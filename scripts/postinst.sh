#!/bin/bash
if [ -d "/opt/zimbra/jetty_base/webapps" ]; then
	if lsattr -d /opt/zimbra/jetty_base/webapps/ | grep -qE '\b[i]\b'; then
		echo "Immutable attribute already set on the /opt/zimbra/jetty_base/webapps/ (zimbra-mbox-war)"
		lsattr -d /opt/zimbra/jetty_base/webapps/
	else
		echo "**** apply restrict write access for /opt/zimbra/jetty_base/webapps/ (zimbra-mbox-war) ****"
		lsattr -d /opt/zimbra/jetty_base/webapps/
		chattr +i -R /opt/zimbra/jetty_base/webapps/
	fi
fi
