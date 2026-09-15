# Concepts

This document provides a glossary of terms and concepts used in the context of our project. It serves as a reference for understanding the terminology and key ideas that are relevant to our work!

## Frontend
The frontend refers to the user-facing part of the application, including the user interface (UI) and user experience (UX). It is responsible for displaying information to the user and allowing them to interact with the application.

## Backend
The backend is the server-side part of the application that handles business logic, data processing, and communication with the database. It provides APIs for the frontend to interact with.

## API
An Application Programming Interface (API) is a set of rules and protocols (standards) that allows different software components to communicate with each other. In our project, the backend exposes RESTful APIs for the frontend to consume.

APIs always sound a little abstract if you haven't worked with them before.

Basically, think of it like this. Imagine a TV remote. You can press the power button to turn the TV on or off, or you can press the volume buttons to adjust the sound. You can press the channel buttons to change the channel, the settings buttons to adjust the settings, and so on.

We would say that the remote "exposes" a set of buttons that you can use to control the TV. You don't need to know how the TV works internally; you just need to know which buttons to press to get the desired result.

Let's say you're building a robot that can control the TV. Because the TV already comes with a remote, you just use that remote (API) to control the TV, instead of having to code up your own control system and embed a infrared sensor into the robot. If you would like the robot to turn on the TV, you would program it to press the power button on the remote. If you would like the robot to change the channel, you would program it to press the channel buttons on the remote.

#### The Remote Being an API
|Action|Flow|
|------|-------------|
|Turn TV on| Robot --> Press Remote's Power Button --> TV turns on|
|Change channel| Robot --> Press Remote's Channel Button --> TV changes channel|
|Change volume| Robot --> Press Remote's Volume Button --> TV changes volume|
|Open settings| Robot --> Press Remote's Settings Button --> TV opens settings|

The remote is like an API: it provides a set of commands (or endpoints) that you can use to interact with the TV (or backend). You don't need to know how the TV works internally; you just need to know which buttons to press (or which API endpoints to call) to get the desired result.

In any software you're building, a lot of times you'll be working with third-party APIs. Even libraries you use in your code are APIs. For example, let's say you're building an application that needs to work with CSV files locally. You could write your own code to read the CSV files, but that would be a lot of work. Instead, you can use a library like OpenCSV (in Java), which provides an API for working with CSV files.

|Action|Flow|
|------|-------------|
|Read CSV file| Your code --> `OpenCSV.readCSV()` --> OpenCSV reads the CSV file and returns the data to your code|
|Write CSV file| Your code --> `OpenCSV.writeCSV()` --> OpenCSV writes the data to the CSV file|
|Get a specific row| Your code --> `OpenCSV.getRow(rowNumber)` --> OpenCSV retrieves the specified row from the CSV file and returns it to your code|
|Get a specific column| Your code --> `OpenCSV.getColumn(columnName)` --> OpenCSV retrieves the specified column from the CSV file and returns it to your code|

Something like that.

So if you're working on a Java application, you can use third-party libraries like OpenCSV in Java. Working with them as APIs is easier since most of the time you're just calling methods, etc.

Some APIs work are the network on a server. So instead of just calling methods, you have to send HTTP requests to the API endpoints. In a sense, you're still "pressing buttons" on a remote control, but now the remote is on a server somewhere. The backend in our project exposes RESTful APIs that the frontend can call over HTTP to perform actions like creating a reservation, retrieving room information, or managing attendees. So something like this:

|Action|Flow|
|------|-------------|
|Create a reservation| Frontend --> HTTP POST request to `/reservations` endpoint --> Backend processes the request and creates a reservation in the database|
|Retrieve room information| Frontend --> HTTP GET request to `/rooms/{roomId}` endpoint --> Backend processes the request and returns the room information|
|Manage attendees| Frontend --> HTTP PUT request to `/reservations/{reservationId}/attendees` endpoint --> Backend processes the request and updates the attendee information|

etc.

APIs don't have to be third-party libraries; we can build our own! That's what we did in this project. The backend team built our own RESTful API that the frontend team can use to interact with the backend and perform actions like creating reservations, retrieving room information, and managing attendees. The frontend team can call these API endpoints over HTTP to perform the desired actions without needing to know how the backend is implemented internally. So these requests are still happening over the network, but the server that's handling these requests is our own backend server. Many companies and positions will say that they want you to create APIs, just like we did (and will do) in this project. So it's a good skill to have.

## RESTful API
A RESTful (REpresentational State Transfer) API is an architectural style for designing networked applications. It uses HTTP requests to perform CRUD (Create, Read, Update, Delete) operations on resources. In our project, the backend exposes RESTful APIs that the frontend can call to interact with the application.

The reason why it's called RESTful is because it follows the principles of REST, which include:
* statelessness
* client-server architecture, and
* a uniform interface.

This means that each request from the frontend to the backend should contain all the information needed to process the request, and the backend should _not_ store any client context between requests.

For example, the statelessness property means that the backend does not remember whether a user is logged in or not between requests. Instead, the frontend must include authentication information (like a session token) with each request to prove that the user is logged in.

See more on client-server architecture below.

The uniform interface property (which has a lot of sub-properties) basically point to the fact that the API should have a consistent and predictable structure, making it easier for developers to understand and use. This includes using standard HTTP methods (GET, POST, PUT, DELETE), route structures (`/attendees/{id}`, etc.), and status codes (200 OK, 404 Not Found, etc.) to indicate the result of a request.

## Client-server architecture
Client-server architecture is a design pattern that separates the client (frontend) and server (backend) into distinct entities that communicate over a network. The client is responsible for presenting the user interface and handling user interactions, while the server is responsible for processing requests, managing data, and providing responses to the client.

A typical client-server architecture looks like this (which is what we have in our project):

```

What technologies are used?:       HTML/CSS/JS              Java Spring Boot          PostgreSQL
On what platform?:                   AWS S3                      AWS EC2               Supabase
Architecture components:        Client (Frontend) <-----> Web Server (Backend) <-----> Database

```

The client (frontend), which is usually a webpage running in a browser, sends requests to the server (backend) over the network.

```
Client (Frontend) --> HTTP Request --> Server (Backend)
```

The server processes the request, sanitizes and validates the data, and performs the necessary actions (usually querying the database).

```
Server (Backend) --> Database Query --> Database
```

The database gets the query, retrieves the requested data, and sends it back to the server.

```
Server (Backend) <-- HTTP Response <-- Database
```

The server then sends the response back to the client, which updates the user interface accordingly.

```
Client (Frontend) <-- HTTP Response <-- Server (Backend)
```

We discuss requests and responses in more detail below.

## HTTP requests and responses
An HTTP request is a message sent by the client (frontend) to the server (backend) to request a specific action or resource (more terminology...). It typically includes a method (GET, POST, PUT, DELETE. See below), a URL (endpoint), headers (metadata), and an optional body (data).

### Resource
A resource is any piece of data that can be accessed via the web, such as a file, a database entry, or a user profile. Like in our project, we have data and information about attendees, accounts, and reservations.





## HTTP methods
HTTP (Hypertext Transfer Protocol) is a protocol commonly used for communication between clients and servers on the web.

HTTP methods are used to indicate the desired action to be performed on a "resource" (more terminology...).

We typically perform actions on these resource data, which could be described as CRUD operations (Create, Read, Update, Delete). The four main HTTP methods each correspond to one of these CRUD operations:
* **GET** - Used to retrieve data from a server. It corresponds to the "Read" operation in CRUD.
    * For example, when you want to view a list of reservations, you would send a GET request to the appropriate endpoint.
* **POST** - Used to send data to a server to create a new resource. It corresponds to the "Create" operation in CRUD.
    * For example, when you want to create a new reservation, you would send a POST request with the reservation details to the appropriate endpoint.
* **PUT** - Used to send data to a server to update an existing resource. It corresponds to the "Update" operation in CRUD. 
    * For example, when you want to update the details of an existing reservation, you would send a PUT request with the updated information to the appropriate endpoint.
* **DELETE** - Used to send a request to a server to delete an existing resource. It corresponds to the "Delete" operation in CRUD. 
    * For example, when you want to delete an existing reservation, you would send a DELETE request to the appropriate endpoint.

## Routes/Endpoints
In the context of web development, a route (or endpoint) is a specific URL path that corresponds to a particular resource or action in a web application. Routes are defined in the backend and are used to handle incoming requests from the frontend.

For example, in our project, we have routes for managing reservations, attendees, and accounts. So our backend have routes like `/reservations`, `/attendees`, and `/accounts`. Each route can have different HTTP methods (GET, POST, PUT, DELETE) associated with it to perform different actions on the corresponding resource.

* If I were to make a reservation, I would send a POST (Create) request to the `/reservations` route with the reservation details in the request body.

* If I wanted to view all reservations, I would send a GET request to the same route.

* If I wanted to update a specific reservation, I would send a PUT request to `/reservations/{reservationId}` with the updated information. The `{reservationId}` is a placeholder for the actual ID of the reservation you want to update.

* And if I wanted to delete a reservation, I would send a DELETE request to `/reservations/{reservationId}`.

## HTTP Origin
The HTTP origin is a combination of the
* protocol (http or https) (http**s** being the **secure** version of http)
* domain (or IP address)
    * e.g., `https://example.com`
* port number (if specified) that identifies the source of an HTTP request. It is used to determine whether a request is coming from a trusted source or not.
    * e.g., `https://example.com:8080` (port 8080 is specified here)

The origin is important for security reasons, as it helps prevent cross-site request forgery (CSRF) attacks. In our project, the frontend and backend are hosted on different origins, so we need to configure CORS (Cross-Origin Resource Sharing) to allow the frontend to make requests to the backend.

Simply put, a CSRF attack is when a malicious website tricks a user's browser into making an unwanted request to another website where the user is authenticated. For example, if a user is logged into their bank account and visits a malicious website, that website could send a request to the bank's server to transfer money without the user's knowledge or consent.

Specifying the origin solves this problem. If the bank account is on one origin, `https://bank.com`, and the malicious website is on another origin, `https://malicious.com`, the browser will block the request from the malicious website to the bank's server because they have different origins. This helps protect users from CSRF attacks. That said, servers are defaulted to only allow the same origin to make requests to them. So if the frontend (`http://localhost:3000`) is on a different origin than the backend (`http://localhost:8080`), we need to configure CORS to allow the frontend's origin to make requests to the backend, because they have different origins due to the port numbers (3000 vs 8080). Such a small thing, and yet it can cause a lot of headaches if you don't understand it!

## Static vs Dynamic Website
A static website is a website that serves pre-built HTML, CSS, and JavaScript files to the user's browser. The content of a static website does not involve server-side processing. Meaning, it doesn't send out requests to a server, nor is there a server. Static websites are typically faster and easier to deploy, as they do not require server-side processing or database interactions. An example of a static website is a personal portfolio or a blog that serves pre-built HTML files to the user's browser. There is no backend server involved in storing user data, etc.

A dynamic website, on the other hand, generates content on-the-fly based on user interactions or data from a database. Dynamic websites often involve server-side processing and can provide personalized experiences for users. An example of a dynamic website is an e-commerce platform that displays product information based on user preferences or search queries. In our project, the backend serves as the dynamic part of the application, handling requests from the frontend and interacting with the database to provide relevant data.

## AWS
AWS is always a big topic...

### S3 Bucket
An S3 bucket is a container for storing objects in Amazon's S3 technology. It is similar to a cloud drive! It is flexible and can be used to store a wide variety of data, including images, videos, documents, and backups. This is where we store our frontend files (HTML, CSS, JS) for our project. The S3 bucket is configured to serve these files as a static website, allowing users to access the frontend of our application through a web browser.

### EC2
EC2 is an Amazon technology that provides scalable computing in the cloud.

Basically, we can rent a virtual computer (or server) from Amazon to run our backend application. The cloud part refers to the fact that the resources are not physically located in our own data center, but rather in Amazon's data centers.

Scalable means that we can easily adjust the resources (CPU, memory, storage) allocated to our EC2 instance based on the needs of our application. Think about it like this. You're managing a coffee shop, and sometimes it's slow (little customers) and sometimes it's busy (many customers). Maybe in one day, we'd experience a surge in customers. If we initially scheduled only 2 baristas because it's pretty slow, then we're going to have a lot of unhappy customers when we experience the surge because then they'll be served very slowly. However, if we can call in additional baristas as needed, like 5 more, then we're matching the demand.

Similarly, if our application experiences a sudden increase in traffic, AWS can automatically scale up the EC2 instance to handle the load, and if the traffic decreases, it can scale down to save costs. Imagine if we were to run our own server, then we would have to buy the hardware, set it up, and maintain it ourselves. If we experience sudden traffic and our server is having a hard time keeping up, then we would have to buy more hardware and set it up ourselves. This is a lot of work and can be expensive. With AWS, we can easily scale our EC2 instance up or down based on the needs of our application without having to worry about the underlying infrastructure. That is one benefit of using cloud computing services like AWS.

An EC2 instance is a virtual machine or computer in AWS's cloud infrastructure. The way it works is that they have these huge computers, like the ones you'd see when you look up servers or data centers. These machines have no monitors, keyboards, or mice; they're just big computers on racks. They have a lot of processing power, memory, and storage. AWS takes these big computers and divides them into smaller virtual machines (VMs) that can run independently. A virtual machine is a software implementation of a physical machine. Just like how each of our own laptops or computers come with its own, single operating system like Windows or MacOS, a virtual machine is its own operating system--basically a computer on its own! Think of it like running another Windows OS inside of an existing Windows OS. In the case of a server, though, Linux is usually the best, lightweight OS to go for.

So each computer on AWS's rack is divided into multiple software VMs. Imagine a big computer on a rack that has 64GB of RAM and 16 CPU cores. AWS can divide that into 4 VMs, each with 16GB of RAM and 4 CPU cores. Each VM can run its own operating system and applications independently of the others. So maybe one VM is used by company A, and another VM is used by company B. This allows multiple users to share the same physical hardware while still having their own isolated environment. Pretty cool, huh? So each VM is like a separate computer that can run its own operating system and applications, even though they're all running on the same physical hardware. This is what makes cloud computing low-cost and scalable. Any time someone wants to rent a new VM, AWS can just spin up another VM because of the amount of physical resources they have.

Like mentioned before, each VM is called an EC2 instance. We can rent an EC2 instance from AWS to run our backend application. This allows us to deploy our backend code and make it accessible to users over the internet. The EC2 instance can be configured with different specifications (CPU, memory, storage) based on the needs of our application. Lower specifications usually means lower cost, but sometimes that's really all we need.

## Cookies
A **cookie** is a small piece of data that is stored on the _user's browser_ by a website. Cookies are used to remember information about the user, such as their preferences, login status, or session information. In our project, cookies are used to store authentication tokens that allow the frontend to make authenticated requests to the backend.

The flow of authentication is currently as follows:
1. Before a user logs in to our application or is logged out, the frontend does not have any authentication information, not even in a cookie on the user's browser. Even if a hacker were to try to make a request to, say, create a reservation without signing in, the backend would reject the request because it does not have any authentication information to verify the user's identity. The backend would respond with a 401 Unauthorized status code, indicating that the request is not allowed.
2. But when a user logs in to our application, the frontend sends the login credentials to the backend.
3. The backend validates the credentials and generates an authentication token.
4. The backend sends the authentication token to the frontend.
5. The frontend then stores this token in a cookie on the user's browser.
6. Next time the user makes a request to the backend, like creating a reservation, the frontend includes the cookie with the authentication token in the request headers. The backend can then verify the user's identity and grant access to protected resources.

The reason why a cookie is needed is because HTTP is a stateless protocol, meaning that each request from the client to the server is independent and does not retain any information about previous requests. So the request does not assume the server knows anything about whether a user is currently logged in in our system; instead, we have to be explicit. We store and send cookies to help maintain this state by allowing the server to recognize returning users.

On the server's end, the way that it knows whether a token is valid or not is by checking it against a database of valid tokens that have been passed out to users. If the token is found in the database, then the server knows that the user is authenticated and can proceed with processing the request. If the token is not found in the database, then the server knows that the user is not authenticated and will respond with a 401 Unauthorized status code. Having an authentication token provides an extra layer of security on top of just a username and a password. Even if a hacker were to steal a user's username and password, they would not be able to access the user's account without the authentication token stored in the cookie.

## Backend Architecture
Our backend architecture follows [Spring Boot Architecture Guide by GeeksForGeeks](https://www.geeksforgeeks.org/springboot/spring-boot-architecture/). It's a layered architecture that separates the application into different layers, each with its own responsibilities. 

The main layers are:

* **Controller Layer** - This layer handles incoming HTTP requests from the frontend and delegates the processing to the appropriate service layer. It is responsible for validating input, handling errors, and returning responses to the client.
* **Service Layer** - This layer contains the business logic of the application. It processes the data received from the controller layer, interacts with the repository layer, and performs any necessary computations or transformations.
* **Repository Layer** - This layer is responsible for interacting with the database. It provides methods for querying, inserting, updating, and deleting data in the database. The repository layer abstracts the underlying database implementation, allowing the service layer to work with data without worrying about the specifics of the database.
    * **Model Layer** - This layer defines the data structures used in the application. It represents the entities in the database and is used to transfer data between the different layers of the application.

## Relational Database
A relational database is a type of database that organizes data into tables (also called relations) consisting of rows and columns. Each table represents a specific entity, and the relationships between tables are established using keys (primary keys and foreign keys). Relational databases use Structured Query Language (SQL) to manage and manipulate data.

Think of each table as a spreadsheet you might create in Excel or Google Sheets. Each row in the table represents a single record (like a single reservation), and each column represents a specific attribute of that record (like the reservation date, time, and room number). Usually, a primary key, like an ID, uniquely identifies each row in the table. The relationships between tables are established using keys, which allow you to link related data across different tables.

## ORM
ORM (Object-Relational Mapping) is a programming technique that allows developers to interact with a relational database using object-oriented programming concepts. It provides a way to map database tables to Java classes and vice versa, allowing developers to work with data in an object-oriented manner.

For example, if we have a `Reservation` table in our database, we can create a `Reservation` class in our Java code that represents the same data structure. The ORM framework will handle the mapping between the class and the table, allowing us to perform CRUD operations on the `Reservation` objects without writing raw SQL queries.

If the `Reservation` table has columns like `id`, `date`, `time`, and `room_number`, we can create a `Reservation` class with corresponding fields:

```java
public class Reservation {
    private Long id;
    private LocalDate date;
    private LocalTime time;
    private String roomNumber;

    // Getters and setters
}
```

## Spring Boot
Spring Boot is a popular framework for building stand-alone, production-grade Spring-based applications. It provides a way to create a complete application with minimal configuration and setup. In our project, we use Spring Boot to simplify the development process and create a robust application that can be easily deployed and maintained.

## Different ORM Frameworks
There are several ORM frameworks available for different programming languages. Some popular Java ORM frameworks include Spring Data JPA and Hibernate. These frameworks provide a set of tools and libraries that make it easier to work with relational databases in an object-oriented manner. They handle the mapping between Java classes and database tables, allowing developers to focus on writing business logic instead of dealing with low-level database operations.

### JPA (Java Persistence API)
JPA is a Java specification for managing relational data in applications. It provides a way to map Java objects to database tables and vice versa, allowing developers to work with data in an object-oriented manner. In our project, we use JPA to interact with the PostgreSQL database, making it easier to perform CRUD operations on our data.

### Hibernate
Hibernate is an open-source ORM (Object-Relational Mapping) framework for Java that implements the JPA specification. It provides a powerful and flexible way to map Java objects to database tables and vice versa, allowing developers to work with data in an object-oriented manner.

## Supabase
TODO

## Agile
TODO