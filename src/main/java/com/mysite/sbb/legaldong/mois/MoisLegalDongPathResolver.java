package com.mysite.sbb.legaldong.mois;

import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 설정 문자열을 실제 파일시스템 Path로 해석한다.
 *
 * 요구사항 배경
 * - 운영(Windows)에서는 `C:\Users\...\Desktop\legal` 같은 경로를 사용한다.
 * - 개발/실행 환경이 WSL/Linux인 경우, 위 문자열을 그대로 Path로 만들면 "상대 경로"로 해석될 수 있어
 *   `/mnt/.../project/C:\Users\...` 같은 잘못된 위치에 저장될 수 있다.
 *
 * 동작
 * - Windows 환경: 그대로 Path.of(value)
 * - Linux/WSL 환경: `X:\...` 형태는 `/mnt/x/...` 형태로 변환해 Path로 반환
 */
public final class MoisLegalDongPathResolver {

	private static final Pattern WINDOWS_DRIVE_PATH = Pattern.compile("^([A-Za-z]):[\\\\/](.+)$");

	private MoisLegalDongPathResolver() {
	}

	public static Path resolveBaseDir(String configuredPath) {
		if (configuredPath == null || configuredPath.isBlank()) {
			return Path.of(".");
		}

		String raw = configuredPath.trim();
		if (isWindows()) {
			return Path.of(raw);
		}

		Matcher matcher = WINDOWS_DRIVE_PATH.matcher(raw);
		if (!matcher.matches()) {
			return Path.of(raw);
		}

		String drive = matcher.group(1).toLowerCase(Locale.ROOT);
		String rest = matcher.group(2).replace('\\', '/');
		return Path.of("/mnt/" + drive + "/" + rest);
	}

	private static boolean isWindows() {
		String osName = System.getProperty("os.name", "");
		return osName.toLowerCase(Locale.ROOT).contains("windows");
	}
}

