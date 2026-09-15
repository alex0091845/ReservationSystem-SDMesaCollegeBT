# Common Frontend Terms

This document provides a glossary of common frontend terms used in the context of our project.

## UI-related
### Modal
A modal is a UI element that appears on top of the main content, usually to display additional information or prompt the user for input. It typically requires the user to interact with it before returning to the main content. Or, the user might cancel the operation by clicking outside the modal or hitting the "Cancel" button. It's often used for forms, alerts, or confirmations.

For example, in our project, when a user clicks on the "Reserve" button, a modal appears asking for confirmation or additional details before proceeding with the reservation. The modal appears as a smaller, rectangular box in the center of the screen, with a semi-transparent background that dims the main content. The user can either confirm or cancel the action within the modal, or cancel by clicking outside the modal.

## JavaScript-related
### Fetch
The `fetch` function is a built-in JavaScript function that allows you to make HTTP requests to a server and retrieve data. It returns a Promise that resolves to the Response object representing the response to the request.

### Callback (Function)
A callback is a _function_ that is _passed as an argument_ to another function and is executed after the completion of that function. It allows you to handle asynchronous operations and perform actions once the operation is finished.

For example, in our project, when a user clicks on the "Reserve" button, the callback function `handleReservation` is passed to the `fetch` function to handle the response from the server. The `fetch` function is designed such that once it successfully gets the data from the server, it would then call the callback function. That callback function, `handleReservation`, would then process the response and update the UI accordingly. This way, we can ensure that the UI is updated only after the data has been successfully fetched from the server.

An example looks like this:

```javascript

function fetchData(callbackParam) {
  fetch('https://api.example.com/data')
    .then(response => response.json())
    .then(data => {
      // The data here is the response from the server after the fetch operation is completed.
      // Call the callback function with the fetched data
      // So in reality, callbackParam is a function passed into this function
      // so that it can be called.
      callbackParam(data);
    })
    .catch(error => {
      console.error('Error fetching data:', error);
    });
}
```

### Promise & Asynchronous Programming
A Promise is an object that represents the eventual completion (or failure) of an asynchronous operation and its resulting value. It allows you to write asynchronous code in a more readable and manageable way, avoiding callback hell.

Think of it this way. A Promise is just like when you make a promise to someone. You say, "I promise to do something for you." The person can then do other stuff while waiting for you to fulfill that promise. In programming, a Promise is an object that represents a value that may not be available yet but will be resolved or fulfilled in the future.

An example can be, let's say you want to fetch some data from a server. You can use the `fetch` function, which returns a Promise. You can then use the `.then()` method to specify what should happen when the Promise is resolved (i.e., when the data is successfully fetched) or use the `.catch()` method to handle any errors that may occur during the fetch operation.

During the time that the Promise is pending (i.e., the data is being fetched), you can show a loading spinner or some other indication to the user that the operation is in progress. Once the Promise is resolved, you can update the UI with the fetched data. A Promise does not block the execution of other code. Blocking code can give a user experience of "freezing" or "hanging" the application while the data is being retrieved (like your cursor showing spinning circle and nothing is responding). We don't want that. Asynchronous programming allows us to perform tasks in the background while still allowing the user to interact with the application (e.g., show a loading spinner while the data is being fetched).