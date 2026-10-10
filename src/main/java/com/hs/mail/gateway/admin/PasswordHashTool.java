package com.hs.mail.gateway.admin;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.Console;

/**
 * 관리자 로그인 비밀번호의 BCrypt 해시를 만드는 도구. 출력값을 application.yml의
 * {@code gateway.admin.users[].password-hash}에 넣는다. 비밀번호는 인자로 받지 않고 입력받는다
 * (셸 기록/프로세스 목록에 남지 않도록).
 *
 * <pre>
 * java -Dloader.main=com.hs.mail.gateway.admin.PasswordHashTool \
 *      -cp hedwig-spam-gateway.jar org.springframework.boot.loader.PropertiesLauncher
 * </pre>
 * 표준입력이 파이프이면 한 줄을 비밀번호로 읽는다(스크립트용).
 */
public final class PasswordHashTool {

    private PasswordHashTool() {
    }

    public static void main(String[] args) throws Exception {
        String password;
        Console console = System.console();
        if (console != null) {
            char[] a = console.readPassword("새 비밀번호: ");
            char[] b = console.readPassword("다시 입력: ");
            if (a == null || b == null || !new String(a).equals(new String(b))) {
                System.err.println("비밀번호가 일치하지 않습니다.");
                System.exit(1);
                return;
            }
            password = new String(a);
        } else {
            password = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, "UTF-8")).readLine();
        }
        if (password == null || password.length() < 8) {
            System.err.println("비밀번호는 8자 이상이어야 합니다.");
            System.exit(1);
            return;
        }
        System.out.println(new BCryptPasswordEncoder().encode(password));
    }
}
