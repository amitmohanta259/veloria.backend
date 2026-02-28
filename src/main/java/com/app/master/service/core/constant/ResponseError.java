package com.app.master.service.core.constant;

public interface ResponseError {

    // Contact number
    String REQUIRED_CONTACT_NUMBER = "Please enter valid contact number.";
    String CONTACT_NUMBER_LENGTH = "Contact number size should be between 10 and 13 characters.";
    String VALID_CONTACT_NUMBER = "Please enter valid contact number.";

    // First name or Last name
    String REQUIRED_FIRST_NAME = "Please enter valid first name.";
    String REQUIRED_LAST_NAME = "Please enter valid last name.";
    String VALID_FIRST_NAME = "Please enter valid first name.";
    String VALID_LAST_NAME = "Please enter valid last name.";
    String LENGTH_FIRST_NAME = "First name length must be between 2 and 64 characters.";
    String LENGTH_LAST_NAME = "Last name length must be between 2 and 64 characters.";

    //Description
    String DESCRIPTION_LENGTH ="Description size should be between 1 and 1024 characters.";

    //FAX
    String VALID_FAX = "Please enter valid fax number.";

    //Email
    String VALID_EMAIL = "Please enter valid email.";
    String REQUIRED_EMAIL = "Please enter valid email.";
    String EMAIL_SIZE = "Email size should be between 5 and 255 characters.";
    String ALREADY_TAKEN_EMAIL = "Provided email has already been taken.";
    String User_ALREADY_TAKEN_EMAIL = "User already exist with same provided email.";

    // User name
    String VALID_USER_NAME = "User name is invalid. Minimum 3, maximum 64 alphanumeric characters are allowed.";
    String REQUIRED_USER_NAME = "Please enter valid user name.";
    String USER_ALREADY_TAKEN_USER_NAME = "User already exist with this username.";


    //Password
    String REQUIRED_PASSWORD  = "Please enter valid password.";
    String VALID_PASSWORD = "Please enter valid password.";

    //Role
    String REQUIRED_ROLE  = "Please enter valid role";
    String VALID_ROLE = "Please enter valid role.";
    String REQUIRED_CREDENTIALS = "Please enter therapist credentials";

    //Login
    String ACCOUNT_NOT_FOUND = "Account not found.";
    String INVALID_CREDENTIAL = "Please enter valid credentials.";
    String INVALID_CLIENT_CREDENTIALS = "Client credentials are invalid.";
    String INVALID_CLIENT_AUTHORIZATION = "Client is not authorized";
    String ACCESS_TOKEN = "access_token";

    //Keycloak errors
    String CLIENT_NOT_CREATED = "Client has not been created, status : ";
    String REALM_NOT_CREATED = "Realm has not been created, status : ";
}
