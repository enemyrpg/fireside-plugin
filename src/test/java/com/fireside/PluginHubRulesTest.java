package com.fireside;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Guards the Plugin Hub rules ("Rejected or Rolled-Back Features" on the
 * RuneLite wiki: forbidden language features and restricted APIs) by scanning
 * the plugin sources ({@code src/main/java}) for forbidden APIs. Comments and
 * string literals are ignored, so a Javadoc mention doesn't fail the build.
 * Test code is not scanned: it is not part of the plugin jar.
 */
public class PluginHubRulesTest
{
	private static final Path MAIN_SOURCES = Paths.get("src", "main", "java");

	/** Rule name to pattern, matched against code with comments and strings removed. */
	private static final Map<String, Pattern> FORBIDDEN = new LinkedHashMap<>();

	static
	{
		forbid("LinkBrowser.open (use LinkBrowser.browse for URLs)", "\\bLinkBrowser\\s*\\.\\s*open\\s*\\(");
		forbid("java.awt.Desktop", "\\bDesktop\\b");
		forbid("Thread.sleep", "\\bThread\\s*\\.\\s*sleep\\b");
		forbid("Thread.interrupt", "\\.\\s*interrupt\\s*\\(");
		forbid("own Gson instance (inject Gson, use newBuilder())", "\\bnew\\s+Gson\\s*\\(|\\bGsonBuilder\\b");
		forbid("own OkHttpClient instance (inject it, use newBuilder())",
			"\\bnew\\s+OkHttpClient\\s*\\(|\\bOkHttpClient\\s*\\.\\s*Builder\\b");
		forbid("network client other than OkHttp",
			"\\bHttpURLConnection\\b|\\bHttpsURLConnection\\b|\\bjava\\s*\\.\\s*net\\s*\\.\\s*http\\b|\\bURLConnection\\b");
		forbid("reflection", "\\bjava\\s*\\.\\s*lang\\s*\\.\\s*(reflect|invoke)\\b|\\bClass\\s*\\.\\s*forName\\b"
			+ "|\\.\\s*getDeclared\\w*\\s*\\(|\\.\\s*setAccessible\\s*\\(");
		forbid("dynamic class loading", "\\bClassLoader\\b");
		forbid("JNI/JNA/Unsafe", "\\bnative\\b|\\bcom\\s*\\.\\s*sun\\s*\\.\\s*jna\\b|\\bUnsafe\\b|\\bSystem\\s*\\.\\s*load");
		forbid("subprocess", "\\bProcessBuilder\\b|\\bProcess\\b|\\.\\s*exec\\s*\\(");
		forbid("java.lang.Runtime", "\\bRuntime\\s*\\.\\s*getRuntime\\b|\\bjava\\s*\\.\\s*lang\\s*\\.\\s*Runtime\\b");
		forbid("java.awt.Robot", "\\bRobot\\b");
		forbid("java.awt.KeyboardFocusManager", "\\bKeyboardFocusManager\\b");
		forbid("Java serialization", "\\bObject(Input|Output)Stream\\b|\\bSerializable\\b");
		forbid("Filepath.Unchecked (restricted)", "\\bFilepath\\s*\\.\\s*Unchecked\\b");
		forbid("java.io file API (use Filepath)", "\\bjava\\s*\\.\\s*io\\s*\\.\\s*File\\b|\\bnew\\s+File\\s*\\("
			+ "|\\bFileInputStream\\b|\\bFileOutputStream\\b|\\bFileReader\\b|\\bFileWriter\\b|\\bRandomAccessFile\\b");
		forbid("java.nio file API (use Filepath)",
			"\\bjava\\s*\\.\\s*nio\\s*\\.\\s*file\\s*\\.\\s*(Files|Paths|Path)\\b|\\bFiles\\s*\\.|\\bPaths\\s*\\.");
		// For a stream, ImageIO buffers through a temp file; ImageIO.setUseCache would change the whole client.
		forbid("ImageIO temp-file IO (use MemoryCacheImageOutputStream)",
			"\\bImageIO\\s*\\.\\s*(write|createImageOutputStream|setUseCache|setCacheDirectory)\\b"
			+ "|\\bFileCacheImage(Output|Input)Stream\\b|\\bFileImage(Output|Input)Stream\\b");
		forbid("restricted client API", "\\.\\s*(menuAction|hopToWorld|setCanSendPackets)\\s*\\(");
		forbid("net.runelite package", "^\\s*package\\s+net\\s*\\.\\s*runelite\\b");
	}

	private static void forbid(String rule, String regex)
	{
		FORBIDDEN.put(rule, Pattern.compile(regex));
	}

	@Test
	public void pluginSourcesUseNoForbiddenApis() throws IOException
	{
		List<Path> files;
		try (Stream<Path> walk = Files.walk(MAIN_SOURCES))
		{
			files = walk.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
		}
		assertTrue("no sources found in " + MAIN_SOURCES.toAbsolutePath(), !files.isEmpty());

		List<String> problems = new ArrayList<>();
		for (Path file : files)
		{
			String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
			problems.addAll(findViolations(file.toString(), source));
		}
		assertTrue("Forbidden Plugin Hub APIs:\n" + String.join("\n", problems), problems.isEmpty());
	}

	@Test
	public void ignoresCommentsAndStrings()
	{
		String source = "package com.fireside;\n"
			+ "/** Never call {@link Thread#sleep} or Thread.sleep(1). */\n"
			+ "class A {\n"
			+ "  // LinkBrowser.open(dir) is forbidden\n"
			+ "  /* new Gson() */ String s = \"new FileInputStream(x)\"; char c = '\"';\n"
			+ "  String url = \"http://x\"; void f() { Thread.sleep(1); }\n"
			+ "}\n";
		List<String> problems = findViolations("A.java", source);
		assertEquals(problems.toString(), 1, problems.size());
		assertTrue(problems.get(0), problems.get(0).startsWith("A.java:6: Thread.sleep"));
		assertTrue(problems.get(0), problems.get(0).contains("matched \"Thread.sleep\""));
	}

	@Test
	public void findsEachFileStreamClass()
	{
		for (String type : new String[]{"FileInputStream", "FileOutputStream", "FileReader", "FileWriter",
			"RandomAccessFile"})
		{
			List<String> problems = findViolations("A.java", "class A { Object o = new " + type + "(p); }");
			assertEquals(type, 1, problems.size());
			assertTrue(problems.get(0), problems.get(0).contains("matched \"" + type + "\""));
		}
	}

	@Test
	public void allowsFilepathAndBrowse()
	{
		String source = "import net.runelite.client.util.Filepath;\n"
			+ "class A { void f(Filepath p) throws Exception { p.openBufferedReader(); LinkBrowser.browse(u); "
			+ "p.joinSegment(\"x\").moveTo(p); } }\n";
		assertEquals(new ArrayList<String>(), findViolations("A.java", source));
	}

	/** Returns one "file:line: rule (matched "token"): code" entry per match. */
	static List<String> findViolations(String fileName, String source)
	{
		String[] lines = stripCommentsAndStrings(source).split("\n", -1);
		List<String> problems = new ArrayList<>();
		for (int i = 0; i < lines.length; i++)
		{
			for (Map.Entry<String, Pattern> rule : FORBIDDEN.entrySet())
			{
				Matcher m = rule.getValue().matcher(lines[i]);
				if (m.find())
				{
					problems.add(fileName + ":" + (i + 1) + ": " + rule.getKey() + " (matched \"" + m.group()
						+ "\"): " + lines[i].trim());
				}
			}
		}
		return problems;
	}

	/**
	 * Replaces comments and the contents of string and char literals with
	 * spaces, keeping line breaks so line numbers stay the same.
	 */
	static String stripCommentsAndStrings(String source)
	{
		StringBuilder out = new StringBuilder(source.length());
		int i = 0;
		int n = source.length();
		while (i < n)
		{
			char c = source.charAt(i);
			char next = i + 1 < n ? source.charAt(i + 1) : '\0';
			if (c == '/' && next == '/')
			{
				while (i < n && source.charAt(i) != '\n')
				{
					out.append(' ');
					i++;
				}
			}
			else if (c == '/' && next == '*')
			{
				out.append("  ");
				i += 2;
				while (i < n && !(source.charAt(i) == '*' && i + 1 < n && source.charAt(i + 1) == '/'))
				{
					out.append(source.charAt(i) == '\n' ? '\n' : ' ');
					i++;
				}
				out.append("  ");
				i += 2;
			}
			else if (c == '"' || c == '\'')
			{
				out.append(c);
				i++;
				while (i < n && source.charAt(i) != c && source.charAt(i) != '\n')
				{
					if (source.charAt(i) == '\\' && i + 1 < n)
					{
						out.append(' ');
						i++;
					}
					out.append(' ');
					i++;
				}
				if (i < n && source.charAt(i) == c)
				{
					out.append(c);
					i++;
				}
			}
			else
			{
				out.append(c);
				i++;
			}
		}
		return out.toString();
	}
}
