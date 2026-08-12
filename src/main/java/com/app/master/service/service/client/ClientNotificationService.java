package com.app.master.service.service.client;

import com.amazonaws.services.simpleemail.AmazonSimpleEmailService;
import com.amazonaws.services.simpleemail.model.*;
import com.amazonaws.services.sns.AmazonSNS;
import com.amazonaws.services.sns.model.PublishRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClientNotificationService {

    private final AmazonSimpleEmailService sesClient;
    private final AmazonSNS snsClient;

    @Value("${aws.sender.email}")
    private String senderEmail;

    public void sendOtpEmail(String toEmail, String otp) {
        try {
            SendEmailRequest request = new SendEmailRequest()
                    .withSource(senderEmail)
                    .withDestination(new Destination().withToAddresses(toEmail))
                    .withMessage(new Message()
                            .withSubject(new Content("Your Veloria Login OTP"))
                            .withBody(new Body()
                                    .withHtml(new Content(buildEmailBody(otp)))
                                    .withText(new Content("Your Veloria OTP is: " + otp + ". It expires in 5 minutes."))));
            sesClient.sendEmail(request);
            log.info("OTP email sent to {}", toEmail);
        } catch (Exception e) {
            log.error("Failed to send OTP email to {}: {}", toEmail, e.getMessage());
        }
    }

    public void sendOtpSms(String phone, String otp) {
        try {
            // Normalise to E.164 if needed
            String e164 = phone.startsWith("+") ? phone : "+91" + phone.replaceAll("[^0-9]", "");
            snsClient.publish(new PublishRequest()
                    .withPhoneNumber(e164)
                    .withMessage("Your Veloria OTP is " + otp + ". Valid for 5 minutes. Do not share this code."));
            log.info("OTP SMS sent to {}", e164);
        } catch (Exception e) {
            log.error("Failed to send OTP SMS to {}: {}", phone, e.getMessage());
        }
    }

    private String buildEmailBody(String otp) {
        return """
                <!DOCTYPE html>
                <html>
                <body style="font-family:sans-serif;background:#faf8f5;margin:0;padding:40px;">
                  <div style="max-width:480px;margin:0 auto;background:#fff;padding:48px 40px;text-align:center;">
                    <p style="font-size:10px;letter-spacing:4px;text-transform:uppercase;color:#aaa;margin-bottom:32px;">VELORIA</p>
                    <h2 style="font-size:22px;color:#1a1a1a;margin-bottom:8px;">Your Login OTP</h2>
                    <p style="color:#888;font-size:14px;margin-bottom:36px;">Use this code to sign in to your Veloria account.</p>
                    <div style="font-size:48px;font-weight:700;letter-spacing:16px;color:#1a1a1a;margin-bottom:36px;">%s</div>
                    <p style="color:#aaa;font-size:12px;">This code expires in <strong>5 minutes</strong>. Do not share it with anyone.</p>
                  </div>
                </body>
                </html>
                """.formatted(otp);
    }

}
