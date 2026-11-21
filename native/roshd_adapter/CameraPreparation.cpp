#include "RoshdEngine.hpp"
#include "utils/Json.hpp"
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <array>
#include <cmath>
#include <filesystem>
#include <sstream>

namespace roshd {
// Application framing/pose preparation. The unchanged source engine must
// independently verify its homography, every registration row and answers.
std::string prepareCameraJson(const std::string& request) {
  const auto unchanged=[](const char* reason) { return std::string("{\"applied\":false,\"reason\":\"")+reason+"\"}"; };
  try {
    const auto path=omr::json::stringForKey(request,"imagePath").value_or("");
    const auto output=omr::json::stringForKey(request,"outputPath").value_or("");
    if(path.empty() || output.empty() || std::filesystem::path(output).extension()!=".bmp")return unchanged("invalid_request");
    cv::Mat original=cv::imread(omr::json::stripFileUri(path),cv::IMREAD_GRAYSCALE);
    if(original.empty())return unchanged("unreadable_image");
    const double scale=std::min(1.0,1600.0/std::max(original.cols,original.rows));
    cv::Mat gray;cv::resize(original,gray,cv::Size(),scale,scale,cv::INTER_AREA);
    struct Marker { cv::Point2f center;double area;std::vector<cv::Point2f> corners; };
    std::vector<Marker> markers;
    for(const int threshold:{90,150,210}) {
      cv::Mat mask;cv::threshold(gray,mask,threshold,255,cv::THRESH_BINARY_INV);
      std::vector<std::vector<cv::Point>> contours;
      cv::findContours(mask,contours,cv::RETR_LIST,cv::CHAIN_APPROX_SIMPLE);
      for(const auto& contour:contours) {
        const auto area=cv::contourArea(contour);
        if(area<gray.total()*.00008 || area>gray.total()*.008)continue;
        std::vector<cv::Point> quad;cv::approxPolyDP(contour,quad,cv::arcLength(contour,true)*.035,true);
        if(quad.size()!=4 || !cv::isContourConvex(quad))continue;
        const auto box=cv::minAreaRect(quad);
        const double ratio=box.size.width/std::max(1.f,box.size.height);
        // A real square projects to roughly 0.32:1 near 60° tilt. This is
        // only a proposal: the four complete projected square shapes below,
        // their paper rings and the source engine's rail proof remain required.
        if(ratio<.25 || ratio>4.0 || area/(box.size.width*box.size.height)<.60)continue;
        // QR finder outlines have square contours but contain white holes.
        const auto bounds=cv::boundingRect(quad);
        cv::Mat inside=cv::Mat::zeros(bounds.size(),CV_8U);
        auto local=quad;for(auto& p:local)p-=bounds.tl();
        cv::fillConvexPoly(inside,local,255);
        if(cv::mean(mask(bounds),inside)[0]<238)continue;
        const auto center=box.center;
        if(std::any_of(markers.begin(),markers.end(),[&](const auto& m){return cv::norm(m.center-center)<std::sqrt(area)*.35;}))continue;
        std::vector<cv::Point2f> corners;for(const auto& p:quad)corners.emplace_back(p);
        markers.push_back({center,area,std::move(corners)});
      }
    }
    if(markers.size()<4 || markers.size()>16)return unchanged("four_squares_unconfirmed");
    std::vector<cv::Point2f> accepted,acceptedInterior;bool rectify=false;int matches=0;
    for(size_t a=0;a<markers.size();++a)for(size_t b=a+1;b<markers.size();++b)
    for(size_t c=b+1;c<markers.size();++c)for(size_t d=c+1;d<markers.size();++d) {
      std::array<Marker,4> m{markers[a],markers[b],markers[c],markers[d]};
      cv::Point2f centroid;for(const auto& marker:m)centroid+=marker.center*.25f;
      // Cyclic order survives image roll. Sorting the top two by y folds the
      // quadrilateral at diagonal rotations, especially under perspective.
      std::sort(m.begin(),m.end(),[&](const auto& x,const auto& y) {
        return std::atan2(x.center.y-centroid.y,x.center.x-centroid.x)<
               std::atan2(y.center.y-centroid.y,y.center.x-centroid.x);
      });
      // A strongly foreshortened long edge can be shorter than the short edge.
      // Try both physical axes; the four observed square SHAPES disambiguate
      // them. Area alone cannot distinguish an anisotropic 90-degree mapping.
      for(int axis=0;axis<2;++axis) {
      if(axis)std::rotate(m.begin(),m.begin()+1,m.end());
      std::vector<cv::Point2f> q;for(const auto& marker:m)q.push_back(marker.center);
      if(!cv::isContourConvex(q))continue;
      const double w=(cv::norm(q[1]-q[0])+cv::norm(q[2]-q[3]))*.5;
      const double h=(cv::norm(q[3]-q[0])+cv::norm(q[2]-q[1]))*.5;
      const double aspect=std::min(w,h)/std::max(w,h);
      const double area=std::abs(cv::contourArea(q));
      if(aspect<.30 || area<gray.total()*.09)continue;
      const double horizontalRatio=cv::norm(q[1]-q[0])/cv::norm(q[2]-q[3]);
      const double verticalRatio=cv::norm(q[3]-q[0])/cv::norm(q[2]-q[1]);
      if(horizontalRatio<.45 || horizontalRatio>2.22 || verticalRatio<.45 || verticalRatio>2.22)continue;
      // Four 7 mm squares on a 129 × 191 mm rectangle, with a 9.5 mm
      // center-to-paper-edge margin. Check each local projected square size.
      const float pw=148,ph=210;
      const std::vector<cv::Point2f> physical{{9.5,9.5},{pw-9.5f,9.5},{pw-9.5f,ph-9.5f},{9.5,ph-9.5f}};
      const auto transform=cv::getPerspectiveTransform(physical,q);
      bool consistent=true;
      for(int i=0;i<4 && consistent;++i) {
        const auto p=physical[i];
        std::vector<cv::Point2f> square{{p.x-3.5f,p.y-3.5f},{p.x+3.5f,p.y-3.5f},{p.x+3.5f,p.y+3.5f},{p.x-3.5f,p.y+3.5f}},projected;
        cv::perspectiveTransform(square,projected,transform);
        const double expected=std::abs(cv::contourArea(projected));
        consistent=m[i].area/expected>.70 && m[i].area/expected<1.35;
        double vertexError=0,maximumError=0;
        for(const auto& vertex:projected) {
          double nearest=1e30;for(const auto& observed:m[i].corners)nearest=std::min(nearest,cv::norm(vertex-observed));
          vertexError+=nearest;maximumError=std::max(maximumError,nearest);
        }
        consistent=consistent && vertexError/(4*std::sqrt(expected))<.14 && maximumError/std::sqrt(expected)<.23;
        // Surrounding paper must be lighter on all sides; a dark object on
        // another dark object cannot masquerade as a registration square.
        std::vector<cv::Point2f> ring{{p.x-5.5f,p.y},{p.x+5.5f,p.y},{p.x,p.y-5.5f},{p.x,p.y+5.5f}},samples;
        cv::perspectiveTransform(ring,samples,transform);
        const int center=gray.at<unsigned char>(cvRound(q[i].y),cvRound(q[i].x));
        for(const auto& s:samples) {
          if(s.x<0 || s.y<0 || s.x>=gray.cols-1 || s.y>=gray.rows-1 || gray.at<unsigned char>(cvRound(s.y),cvRound(s.x))-center<30)consistent=false;
        }
      }
      if(!consistent)continue;
      const std::vector<cv::Point2f> paper{{0,0},{pw,0},{pw,ph},{0,ph}};
      std::vector<cv::Point2f> bounds;cv::perspectiveTransform(paper,bounds,transform);
      if(std::any_of(bounds.begin(),bounds.end(),[&](const auto& p){return !std::isfinite(p.x) || !std::isfinite(p.y) || p.x< -scale*4 || p.y< -scale*4 || p.x>gray.cols+scale*4 || p.y>gray.rows+scale*4;}))continue;
      accepted=bounds;
      const std::vector<cv::Point2f> interior{{.7,.7},{pw-.7f,.7},{pw-.7f,ph-.7f},{.7,ph-.7f}};
      cv::perspectiveTransform(interior,acceptedInterior,transform);++matches;
      const auto edge=q[1]-q[0];
      const double angle=std::atan2(edge.y,edge.x)*180/CV_PI;
      const double quarterRoll=std::abs(std::remainder(angle,90.));
      rectify=omr::json::boolForKey(request,"forceRectification").value_or(false) ||
        quarterRoll>7 || std::abs(std::log((w/h)/(129./191.)))>std::log(1.10) ||
        std::abs(std::log(horizontalRatio))>std::log(1.18) || std::abs(std::log(verticalRatio))>std::log(1.18);
      }
    }
    if(matches!=1)return unchanged(matches?"ambiguous_squares":"square_geometry_unconfirmed");
    for(auto& p:accepted)p*=float(1/scale);
    for(auto& p:acceptedInterior)p*=float(1/scale);
    const auto crop=cv::boundingRect(accepted)&cv::Rect(0,0,original.cols,original.rows);
    // Avoid changing the source reader's sampling for an already tight photo.
    if(!rectify && crop.area()>original.total()*.85)return unchanged("already_framed");
    const auto sourceSize=original.size();original.release();
    const auto color=cv::imread(omr::json::stripFileUri(path),cv::IMREAD_COLOR);
    if(color.size()!=sourceSize)return unchanged("image_changed");
    if(rectify) {
      // Only appreciable roll/foreshortening needs another interpolation. Use
      // original color pixels exactly once, never an enlarged detection image.
      // The source QR decoder and all source rail rows still decide validity.
      const double density=std::min({cv::norm(accepted[1]-accepted[0])/148.,
        cv::norm(accepted[2]-accepted[3])/148.,cv::norm(accepted[3]-accepted[0])/210.,
        cv::norm(accepted[2]-accepted[1])/210.,2000./210.});
      const cv::Size size(cvRound(148*density),cvRound(210*density));
      if(size.width<500 || size.height<700)return unchanged("insufficient_resolution");
      const std::vector<cv::Point2f> target{{0,0},{float(size.width),0},{float(size.width),float(size.height)},{0,float(size.height)}};
      const auto matrix=cv::getPerspectiveTransform(accepted,target);
      cv::Mat framed;cv::warpPerspective(color,framed,matrix,size,cv::INTER_LINEAR,cv::BORDER_CONSTANT,cv::Scalar(255,255,255));
      // Whiten only the same unprinted 0.7 mm margin used by lossless framing.
      const int mx=std::max(1,cvRound(.7*size.width/148)),my=std::max(1,cvRound(.7*size.height/210));
      framed.rowRange(0,my).setTo(255);framed.rowRange(size.height-my,size.height).setTo(255);
      framed.colRange(0,mx).setTo(255);framed.colRange(size.width-mx,size.width).setTo(255);
      std::filesystem::create_directories(std::filesystem::path(output).parent_path());
      if(!cv::imwrite(output,framed))return unchanged("write_failed");
      std::ostringstream result;result.precision(12);
      result<<"{\"applied\":true,\"reason\":\"four_printed_squares_rectified\",\"losslessEncoding\":true,\"printedPixelsPreserved\":false,\"backgroundCleared\":true,\"resampled\":true,\"width\":"<<size.width<<",\"height\":"<<size.height<<",\"sourceToPrepared\":[";
      for(int i=0;i<9;++i) {if(i)result<<',';result<<matrix.at<double>(i/3,i%3);}result<<"]}";
      return result.str();
    }
    // The ordinary framing path has no interpolation or recompression. Clear
    // only background and the outer 0.7 mm unprinted margin: a closed dark
    // border otherwise hides nested rail contours from RETR_EXTERNAL.
    std::vector<cv::Point> protectedArea;
    for(const auto& p:acceptedInterior)protectedArea.emplace_back(cvRound(p.x)-crop.x,cvRound(p.y)-crop.y);
    cv::Mat mask=cv::Mat::zeros(crop.size(),CV_8U);cv::fillConvexPoly(mask,protectedArea,255);
    cv::Mat framed(crop.size(),CV_8UC3,cv::Scalar(255,255,255));color(crop).copyTo(framed,mask);
    std::filesystem::create_directories(std::filesystem::path(output).parent_path());
    if(!cv::imwrite(output,framed))return unchanged("write_failed");
    std::ostringstream result;result<<"{\"applied\":true,\"reason\":\"four_printed_squares\",\"losslessEncoding\":true,\"printedPixelsPreserved\":true,\"backgroundCleared\":true,\"resampled\":false,\"crop\":["<<crop.x<<','<<crop.y<<','<<crop.width<<','<<crop.height<<"],\"protectedPolygon\":[";
    for(size_t i=0;i<protectedArea.size();++i) { if(i)result<<',';result<<'['<<protectedArea[i].x<<','<<protectedArea[i].y<<']'; }
    result<<"]}";
    return result.str();
  } catch(...) { return unchanged("preparation_failed"); }
}
}
extern "C" const char* roshd_prepare_camera_json(const char* request) {
  const auto value=roshd::prepareCameraJson(request?request:"");auto* copy=new char[value.size()+1];
  std::copy(value.begin(),value.end(),copy);copy[value.size()]=0;return copy;
}
