function generateQR()
{
    let qrInput = document.getElementById("qr-input").value;
    let container = document.getElementById("qrcode");

    container.innerHTML="";
    qr = new QRCode(container,{
        text:qrInput,
        width:"300",
        height:"300",
        colorDark:"#000000",
        colorLight:"#ffffff",
    })
}