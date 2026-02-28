package com.app.master.service.core.constant;

public interface RegexConstant {

    String CLINIC_NAME_REGEX = "^(?=\\s*\\S)[a-zA-Z\\d\\p{Z}\\s-]+$";
    String EMAIL_REGEX = "^[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\\.[a-zA-Z0-9-.]+$";
    String PHONE_REGEX = "^\\+?\\d{10,13}$";
    String PASSWORD_REGEX = "^(?=.*[A-Z])(?=.*[a-z])(?=.*[0-9])(?=.*[^A-Za-z0-9])(?!.*\\s).{8,16}$";
    String WEB_REGEX = "^((ftp|http|https):\\/\\/)?(www.)?(?!.*(ftp|http|https|www.))[a-zA-Z0-9_-]+(\\.[a-zA-Z]+)+((\\/)[\\w#]+)*(\\/\\w+\\?[a-zA-Z0-9_]+=\\w+(&[a-zA-Z0-9_]+=\\w+)*)?\\/?$";
    String NAME_REGEX = "^[a-zA-Z\\-\\'\\s]+$";
    String ZIP_REGEX = "^(?:\\d{5}|\\d{9})$";
    String ONLY_DIGIT = "^[0-9]+$";
    String FAX_REGEX = "^\\+?\\d{10,13}$";
    String FIRST_LAST_NAME = "^[a-zA-Z]+$";
    String USERNAME_REGEX = "^[a-zA-Z0-9]{3,64}$";
    String X_TENANT_ID = "X-TENANT-ID";
    String SUBDOMAIN_REGEX = "^(?!.*\\s)[a-zA-Z]{2,30}$";
    String ROLE_NAME_REGEX = "^[a-z\\-]+$";
}
