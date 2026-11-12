/* One-shot FD receiver for workspace programs. A USB device is not reopened by path.
 * Usage: oc-usb-run <interpreter-or-program> [arguments ...]. */
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/random.h>
#include <sys/stat.h>
#include <stddef.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

static void fail(const char *s) { perror(s); exit(1); }
int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: oc-usb-run PROGRAM [ARG...]\n"); return 2; }
    unsigned char bytes[16]; char id[33];
    if (getrandom(bytes,sizeof(bytes),0) != sizeof(bytes)) fail("getrandom");
    for (size_t i=0;i<sizeof(bytes);i++) snprintf(id+2*i,3,"%02x",bytes[i]);
    struct sockaddr_un addr = {.sun_family=AF_UNIX};
    int length=snprintf(addr.sun_path+1,sizeof(addr.sun_path)-1,"opencode.usb.%s",id);
    int server=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0); if(server<0)fail("socket");
    if(bind(server,(struct sockaddr*)&addr,offsetof(struct sockaddr_un,sun_path)+1+length)<0 || listen(server,1)<0)fail("listen");
    printf("USB_RECEIVER=%s\n",id); fflush(stdout); alarm(150);
    int peer;
    for (;;) {
        peer=accept4(server,NULL,NULL,SOCK_CLOEXEC); if(peer<0)fail("accept");
        struct ucred cred; socklen_t len=sizeof(cred);
        if(getsockopt(peer,SOL_SOCKET,SO_PEERCRED,&cred,&len)==0 && cred.uid==getuid()) break;
        close(peer);
    }
    close(server);
    char byte=0; struct iovec iov={.iov_base=&byte,.iov_len=1};
    union { struct cmsghdr align; char buf[CMSG_SPACE(sizeof(int))]; } control;
    struct msghdr msg={.msg_iov=&iov,.msg_iovlen=1,.msg_control=control.buf,.msg_controllen=sizeof(control.buf)};
    if(recvmsg(peer,&msg,MSG_CMSG_CLOEXEC)!=1 || byte!='U' || (msg.msg_flags&MSG_CTRUNC))fail("receive USB FD");
    struct cmsghdr *c=CMSG_FIRSTHDR(&msg);
    if(!c || c->cmsg_level!=SOL_SOCKET || c->cmsg_type!=SCM_RIGHTS || c->cmsg_len!=CMSG_LEN(sizeof(int))) {fputs("missing USB FD\n",stderr);return 1;}
    int fd;memcpy(&fd,CMSG_DATA(c),sizeof(fd)); close(peer);
    struct stat st;if(fstat(fd,&st)<0 || !S_ISCHR(st.st_mode)){fputs("expected USB character device\n",stderr);return 1;}
    if(fd!=3){if(dup2(fd,3)<0)fail("dup2");close(fd);}
    if(fcntl(3,F_SETFD,0)<0 || setenv("OPENCODE_USB_FD","3",1)<0)fail("prepare USB FD");
    alarm(0);execvp(argv[1],argv+1);fail("exec");
}
