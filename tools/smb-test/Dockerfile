FROM alpine:3.22
RUN apk add --no-cache samba samba-common-tools \
    && adduser -D smbtest \
    && printf 'fixture-password\nfixture-password\n' | smbpasswd -a -s smbtest
COPY smb.conf /etc/samba/smb.conf
COPY start.sh /start.sh
EXPOSE 445
ENTRYPOINT ["/bin/sh", "/start.sh"]