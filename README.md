# SisterMesh

**Helping communities find willing blood donors during emergencies—even when
internet access is unreliable.**

SisterMesh is an Android app designed to connect people who need blood with
community members willing to donate. It focuses on pregnancy and childbirth
emergencies and on places where families cannot depend on mobile data or
reliable phone networks. The project is starting in Akwa Ibom State, Nigeria.

## Download the Android app

[View SisterMesh releases](https://github.com/SisterMesh/Sistermesh/releases).
A production APK will be posted there after it is built for a production
service and signed for distribution. No production APK is available yet.

## How SisterMesh works

People use one of two modes:

- **I need blood** — create a request with the blood group, number of units,
  urgency, and the facility where the blood is needed. The requester enters
  the facility name and location so donors know where to go. A facility
  verification flow is not required to submit a request.
- **I want to donate blood** — receive nearby requests and choose whether to
  respond. Donating is voluntary; people can accept or decline a request.

When internet access is unavailable, nearby Android phones can discover one
another and exchange request updates directly. Participating phones can relay
updates onward, helping requests travel beyond a single nearby connection.
When internet access returns, the app can synchronize updates.

The map supports cached map areas when available and lets a requester place a
pin manually. SisterMesh does not bundle offline map coverage for all of
Nigeria; the map details available offline depend on what has been cached on
the phone.

Voice-assisted request entry can help capture details such as blood group,
units, and facility. Supported on-device speech features work offline after
their language model is available; initial setup may require an internet
connection, and offline language support depends on the device.

## Privacy and safety

- Donors see the information needed to respond, including the requester-entered
  facility and destination location.
- A patient's or beneficiary's name and private notes are not shared with
  donors.
- Donors decide freely whether to respond. A request does not guarantee that a
  donor will be available.
- SisterMesh supports coordination; it does not replace emergency care,
  clinical assessment, or professional decisions about blood compatibility and
  transfusion.

## Initial focus and goals

SisterMesh is being developed for communities in Akwa Ibom State, with the aim
of supporting other communities that face similar connectivity challenges.
Proposed pilot goals include working with up to 20 health facilities over 12
months, supporting offline voice features in three languages, and measuring
request-to-response time, requests receiving a willing donor, and donor
retention. These are project goals, not reported pilot results.

SisterMesh is licensed under the [MIT License](LICENSE). A contribution guide
and deployment materials are planned as the project grows.
