---
title: "Twilio"
description: "Twilio is a cloud communications platform that enables developers to integrate messaging, voice, and video capabilities into their applications."
---

Twilio is a cloud communications platform that enables developers to integrate messaging, voice, and video capabilities into their applications.


Categories: communication


Type: twilio/v1

<hr />



## Connections

Version: 1


### Basic Auth

#### Properties

|      Name       |      Label     |     Type     |     Control Type     |     Description     |     Required        |
|:--------------:|:--------------:|:------------:|:--------------------:|:-------------------:|:-------------------:|
| accountSid | Account SID | STRING | TEXT  |  | true  |
| authToken | Auth Token | STRING | TEXT  |  | true  |





<hr />



## Actions


### Send SMS
Send a new SMS message

#### Properties

|      Name       |      Label     |     Type     |     Control Type     |     Description     |     Required        |
|:--------------:|:--------------:|:------------:|:--------------------:|:-------------------:|:-------------------:|
| accountSid | Account SID | STRING | TEXT  |  The SID of the Account creating the Message resource.  |  false  |
| to | To | STRING | PHONE  |  The recipient's phone number in E.164 format (for SMS/MMS) or channel address, e.g. whatsapp:+15552229999.  |  true  |
| source | Source | STRING | SELECT  |  | true  |
| from | From | STRING | PHONE  |  The sender's Twilio phone number (in E.164 format), alphanumeric sender ID, Wireless SIM, short code, or channel address (e.g., whatsapp:+15554449999). The value of the from parameter must be a sender that is hosted within Twilio and belongs to the Account creating the Message. If you are using messaging_service_sid, this parameter can be empty (Twilio assigns a from value from the Messaging Service's Sender Pool) or you can provide a specific sender from your Sender Pool.  |  true  |
| messagingServiceSid | Messaging Service SID | STRING | TEXT  |  The SID of the Messaging Service you want to associate with the Message. When this parameter is provided and the from parameter is omitted, Twilio selects the optimal sender from the Messaging Service's Sender Pool. You may also provide a from parameter if you want to use a specific Sender from the Sender Pool.  |  true  |
| content | Content | STRING | SELECT  |  | false  |
| body | Body | STRING | TEXT  |  The text content of the outgoing message. Can be up to 1,600 characters in length. SMS only: If the body contains more than 160 GSM-7 characters (or 70 UCS-2 characters), the message is segmented and charged accordingly. For long body text, consider using the send_as_mms parameter.  |  true  |
| mediaUrl | Media URL | [STRING] | ARRAY_BUILDER  |  The URL of media to include in the Message content. jpeg, jpg, gif, and png file types are fully supported by Twilio and content is formatted for delivery on destination devices. The media size limit is 5 MB for supported file types (jpeg, jpg, png, gif) and 500 KB for other types of accepted media. To send more than one image in the message, provide multiple media_url parameters in the POST request. You can include up to ten media_url parameters per message. International and carrier limits apply.  |  false  |
| statusCallback | Status Callback | STRING | URL  |  The URL of the endpoint to which Twilio sends Message status callback requests. URL must contain a valid hostname and underscores are not allowed. If you include this parameter with the messaging_service_sid, Twilio uses this URL instead of the Status Callback URL of the Messaging Service.  |  false  |
| applicationSid | Application SID | STRING | TEXT  |  The SID of the associated TwiML Application. If this parameter is provided, the status_callback parameter of this request is ignored; Message status callback requests are sent to the TwiML App's message_status_callback URL.  |  false  |
| maxPrice | Maximum Price | NUMBER | NUMBER  |  The maximum price in US dollars that you are willing to pay for this Message's delivery. When the max_price parameter is provided, the cost of a message is checked before it is sent. If the cost exceeds max_price, the message is not sent and the Message status is failed.  |  false  |
| provideFeedback | Provide Feedback | BOOLEAN | SELECT  |  Boolean indicating whether or not you intend to provide delivery confirmation feedback to Twilio (used in conjunction with the Message Feedback subresource). Boolean indicating whether or not you intend to provide delivery confirmation feedback to Twilio (used in conjunction with the Message Feedback subresource).  |  false  |
| attempt | Attempt | INTEGER | INTEGER  |  Total number of attempts made (including this request) to send the message regardless of the provider used.  |  false  |
| validityPeriod | Validity Period | INTEGER | INTEGER  |  The maximum length in seconds that the Message can remain in Twilio's outgoing message queue. If a queued Message exceeds the validity_period, the Message is not sent. A validity_period greater than 5 is recommended.  |  false  |
| forceDelivery | Force Delivery | BOOLEAN | SELECT  |  Reserved  |  false  |
| contentRetention | Content Retention | STRING | SELECT  |  Determines if the message content can be stored or redacted based on privacy settings.  |  false  |
| addressRetention | Address Retention | STRING | SELECT  |  Determines if the address can be stored or obfuscated based on privacy settings.  |  false  |
| smartEncoded | Smart Encoded | BOOLEAN | SELECT  |  Whether to detect Unicode characters that have a similar GSM-7 character and replace them.  |  false  |
| persistentAction | Persistent Action | [STRING] | ARRAY_BUILDER  |  Rich actions for non-SMS/MMS channels. Used for sending location in WhatsApp messages.  |  false  |
| shortenUrls | Shorten URLs | BOOLEAN | SELECT  |  For Messaging Services with Link Shortening configured only: A Boolean indicating whether or not Twilio should shorten links in the body of the Message.  |  false  |
| scheduleType | Schedule Type | STRING | SELECT  |  For Messaging Services only: Include this parameter with a value of fixed in conjuction with the send_time parameter in order to schedule a Message.  |  false  |
| sendAt | Send At | {DATE_TIME\(dateTime), STRING\(zoneId)} | OBJECT_BUILDER  |  The time that Twilio will send the message. Must be in ISO 8601 format.  |  false  |
| sendAsMms | Send as MMS | BOOLEAN | SELECT  |  If set to true, Twilio delivers the message as a single MMS message, regardless of the presence of media.  |  false  |
| contentVariables | Content Variables | STRING | TEXT  |  For Content Editor/API only: Key-value pairs of Template variables and their substitution values. content_sid parameter must also be provided. If values are not defined in the content_variables parameter, the Template's default placeholder values are used.  |  false  |
| riskCheck | Risk Check | STRING | SELECT  |  For SMS pumping protection feature only: Include this parameter with a value of disable to skip any kind of risk check on the respective message request.  |  false  |
| contentSid | Content SID | STRING | TEXT  |  For Content Editor/API only: The SID of the Content Template to be used with the Message, e.g., HXXXXXXXXXXXXXXXXXXXXXXXXXXXXX. If this parameter is not provided, a Content Template is not used. Find the SID in the Console on the Content Editor page. For Content API users, the SID is found in Twilio's response when creating the Template or by fetching your Templates.  |  false  |


#### Output



Type: OBJECT


#### Properties

|     Name     |     Type     |     Control Type     |
|:------------:|:------------:|:--------------------:|
| body | STRING | TEXT  |
| numSegments | STRING | TEXT  |
| direction | STRING | TEXT  |
| from | {STRING\(rawNumber)} | OBJECT_BUILDER  |
| to | STRING | TEXT  |
| dateUpdated | {DATE_TIME\(dateTime), STRING\(zoneId)} | OBJECT_BUILDER  |
| price | STRING | TEXT  |
| errorMessage | STRING | TEXT  |
| uri | STRING | TEXT  |
| accountSid | STRING | TEXT  |
| numMedia | STRING | TEXT  |
| status | STRING | TEXT  |
| messagingServiceSid | STRING | TEXT  |
| sid | STRING | TEXT  |
| dateSent | {DATE_TIME\(dateTime), STRING\(zoneId)} | OBJECT_BUILDER  |
| dateCreated | {DATE_TIME\(dateTime), STRING\(zoneId)} | OBJECT_BUILDER  |
| errorCode | INTEGER | INTEGER  |
| currency | {STRING\(currencyCode), INTEGER\(defaultFractionDigits), INTEGER\(numericCode)} | OBJECT_BUILDER  |
| apiVersion | STRING | TEXT  |
| subresourceUris | {} | OBJECT_BUILDER  |








