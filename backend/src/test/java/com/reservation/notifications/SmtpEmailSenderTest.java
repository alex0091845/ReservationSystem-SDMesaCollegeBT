package com.reservation.notifications;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the real SMTP client against a small server inside this test, on the loopback
 * address. Nothing leaves the machine.
 */
class SmtpEmailSenderTest {

    private static final EmailMessage MESSAGE =
        new EmailMessage("ada@sdmesa.edu", "Important: BT-216 Reservation Canceled", "Hello Ada,\n\nCafé lab.\n");

    private final List<String> received = Collections.synchronizedList(new ArrayList<>());
    private ServerSocket server;

    @AfterEach
    void stopServer() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    private EmailSender senderAnswering(String recipientReply) throws IOException {
        server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(() -> serve(recipientReply));
        thread.setDaemon(true);
        thread.start();

        return senderFor(server.getLocalPort());
    }

    private EmailSender senderFor(int port) {
        return new EmailSenderConfig().emailSender(
            "smtp", "bt216@sdmesa.edu", "127.0.0.1", port, "", "", false
        );
    }

    private void serve(String recipientReply) {
        try (Socket client = server.accept();
             BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(client.getOutputStream(), true, StandardCharsets.UTF_8)) {
            reply(out, "220 test");
            boolean data = false;
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                received.add(line);
                if (data) {
                    if (line.equals(".")) {
                        data = false;
                        reply(out, "250 queued");
                    }
                } else if (line.startsWith("RCPT")) {
                    reply(out, recipientReply);
                } else if (line.equals("DATA")) {
                    data = true;
                    reply(out, "354 go");
                } else if (line.equals("QUIT")) {
                    reply(out, "221 bye");
                    return;
                } else {
                    reply(out, "250 ok");
                }
            }
        } catch (IOException ignored) {
            // The test closed the socket.
        }
    }

    private void reply(PrintWriter out, String line) {
        out.print(line + "\r\n");
        out.flush();
    }

    @Test
    void sendsOneMessageToOneRecipient() throws Exception {
        senderAnswering("250 ok").send(MESSAGE);

        assertTrue(received.contains("MAIL FROM:<bt216@sdmesa.edu>"), received.toString());
        assertEquals(1, received.stream().filter(line -> line.startsWith("RCPT")).count());
        assertTrue(received.contains("RCPT TO:<ada@sdmesa.edu>"), received.toString());
        assertTrue(received.contains("Subject: Important: BT-216 Reservation Canceled"), received.toString());
        assertTrue(received.contains("To: ada@sdmesa.edu"), received.toString());
        assertTrue(received.stream().noneMatch(line -> line.toLowerCase().startsWith("bcc")), received.toString());
    }

    @Test
    void permanentRefusalIsNotRetried() throws Exception {
        EmailSender sender = senderAnswering("550 no such user");

        EmailDeliveryException error = assertThrows(EmailDeliveryException.class, () -> sender.send(MESSAGE));

        assertTrue(error.permanent());
        assertFalse(error.getMessage().contains("ada@sdmesa.edu"));
    }

    @Test
    void temporaryRefusalIsRetried() throws Exception {
        EmailSender sender = senderAnswering("451 try later");

        EmailDeliveryException error = assertThrows(EmailDeliveryException.class, () -> sender.send(MESSAGE));

        assertFalse(error.permanent());
    }

    @Test
    void unreachableServerIsRetried() throws Exception {
        int closedPort;
        try (ServerSocket unused = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = unused.getLocalPort();
        }

        EmailDeliveryException error = assertThrows(
            EmailDeliveryException.class, () -> senderFor(closedPort).send(MESSAGE)
        );

        assertFalse(error.permanent());
    }
}
